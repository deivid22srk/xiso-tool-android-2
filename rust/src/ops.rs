//! Operações de alto nível: extrair ISO -> pasta e criar ISO <- pasta.
//!
//! Tudo em streaming (buffer de 1 MiB), com progresso determinado (bytes
//! processados/total) publicado em estado compartilhado lock-free e
//! cancelamento cooperativo verificado a cada chunk.

use std::fs::File;
use std::io::{BufWriter, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use xdvdfs::blockdev::{BlockDeviceRead, OffsetWrapper};
use xdvdfs::layout::VolumeDescriptor;
use xdvdfs::write::fs::{FileEntry, Filesystem, FileType, PathVec};
use xdvdfs::write::img::create_xdvdfs_image;

use crate::error::{cancelled_error, XisoError, ERR_INVALID_ISO, ERR_IO, ERR_NO_DEFAULT_XBE};

#[derive(Copy, Clone, Debug)]
pub enum OpKind {
    Extract,
    Create,
}

const READ_BUFFER: usize = 1024 * 1024;

/// Estado de progresso compartilhado entre a thread de trabalho (produtora) e
/// a thread observadora que emite callbacks JNI. Campos atomics/Mutex => Send+Sync.
pub struct SharedProgress {
    processed: AtomicU64,
    total: AtomicU64,
    current_file: Mutex<String>,
    pub done: AtomicBool,
}

impl SharedProgress {
    pub fn new() -> Self {
        SharedProgress {
            processed: AtomicU64::new(0),
            total: AtomicU64::new(0),
            current_file: Mutex::new(String::new()),
            done: AtomicBool::new(false),
        }
    }

    pub fn set_total(&self, total: u64) {
        self.total.store(total, Ordering::Relaxed);
    }

    pub fn add_processed(&self, amount: u64) {
        self.processed.fetch_add(amount, Ordering::Relaxed);
    }

    pub fn set_current_file(&self, file: &str) {
        if let Ok(mut guard) = self.current_file.lock() {
            *guard = file.to_string();
        }
    }

    pub fn snapshot(&self) -> (u64, u64, String) {
        let file = self
            .current_file
            .lock()
            .map(|g| g.clone())
            .unwrap_or_default();
        (
            self.processed.load(Ordering::Relaxed),
            self.total.load(Ordering::Relaxed),
            file,
        )
    }
}

/// Verificação rápida: o arquivo contém um volume XDVDFS válido (offsets 0, XGD1, XGD2 ou XGD3)?
pub fn is_xiso(path: &Path) -> bool {
    let file = match File::open(path) {
        Ok(f) => f,
        Err(_) => return false,
    };
    let mut dev = match OffsetWrapper::new(file) {
        Ok(d) => d,
        Err(_) => return false,
    };
    xdvdfs::read::read_volume(&mut dev).is_ok()
}

fn open_volume(path: &Path) -> Result<OffsetWrapper<File, std::io::Error>, XisoError> {
    let file = File::open(path)?;
    OffsetWrapper::new(file).map_err(|_| {
        XisoError::new(
            ERR_INVALID_ISO,
            Some(
                "Não foi possível localizar um volume XDVDFS válido na imagem (offsets RAW, XGD1, XGD2 e XGD3 foram testados)"
                    .into(),
            ),
        )
    })
}

/// Caminhos de saída nunca escapam da pasta de destino (bloqueia `..`, caminhos absolutos e NULs).
fn safe_join(base: &Path, rel: &str) -> Result<PathBuf, XisoError> {
    let mut out = base.to_path_buf();
    for component in rel.split('/') {
        let component = component.trim_end_matches('\0');
        if component.is_empty() || component == "." {
            continue;
        }
        if component == ".." || component.starts_with('/') || component.contains('\\') {
            return Err(XisoError::new(
                ERR_IO,
                Some(format!("Caminho inválido dentro da imagem: {rel}")),
            ));
        }
        out.push(component);
    }
    Ok(out)
}

fn relative_path(parent: &str, name: &str) -> String {
    let name = name.trim_end_matches('\0');
    if parent.is_empty() {
        name.to_string()
    } else {
        format!("{}/{}", parent.trim_start_matches('/'), name)
    }
}

/// Extrai a imagem XISO para `out_dir`, preservando a árvore de diretórios.
pub fn extract_iso(
    iso_path: &Path,
    out_dir: &Path,
    cancel: &AtomicBool,
    progress: &SharedProgress,
) -> Result<(), XisoError> {
    if !out_dir.exists() {
        std::fs::create_dir_all(out_dir)?;
    }

    let mut dev = open_volume(iso_path)?;
    let volume: VolumeDescriptor = xdvdfs::read::read_volume(&mut dev)?;
    let tree = volume.root_table.file_tree(&mut dev)?;

    // Passada 1: soma os tamanhos para progresso determinado.
    let mut total: u64 = 0;
    for (_, dirent) in tree.iter() {
        if !dirent.node.dirent.is_directory() {
            total += dirent.node.dirent.data.size as u64;
        }
    }
    progress.set_total(total);

    // Passada 2: cria dirs/arquivos copiando em chunks.
    let mut buffer = vec![0u8; READ_BUFFER];

    for (parent, dirent) in tree.iter() {
        if cancel.load(Ordering::Relaxed) {
            return Err(cancelled_error());
        }

        let name = dirent.name_str::<std::io::Error>()?;
        let name = name.trim_end_matches('\0');
        if name.is_empty() {
            continue; // Entrada sem nome (a CLI oficial também ignora)
        }

        let rel = relative_path(parent, name);
        let dest = safe_join(out_dir, &rel)?;

        if dirent.node.dirent.is_directory() {
            std::fs::create_dir_all(&dest)?;
            continue;
        }

        let size = dirent.node.dirent.data.size as u64;
        if let Some(parent_dir) = dest.parent() {
            std::fs::create_dir_all(parent_dir)?;
        }
        progress.set_current_file(&rel);

        if size == 0 {
            File::create(&dest)?;
            continue;
        }

        let base_offset = dirent.node.dirent.data.offset::<std::io::Error>(0)?;
        let mut out_file = BufWriter::with_capacity(READ_BUFFER, File::create(&dest)?);
        let mut position: u64 = 0;
        while position < size {
            if cancel.load(Ordering::Relaxed) {
                return Err(cancelled_error());
            }
            let chunk = std::cmp::min(buffer.len() as u64, size - position) as usize;
            dev.read(base_offset + position, &mut buffer[..chunk])?;
            out_file.write_all(&buffer[..chunk])?;
            position += chunk as u64;
            progress.add_processed(chunk as u64);
        }
        out_file.flush()?;
    }

    Ok(())
}

/// Soma os bytes da pasta de origem para estimar o tamanho da imagem.
fn estimate_source(root: &Path) -> std::io::Result<u64> {
    let mut total = 0u64;
    let mut stack = vec![root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        for entry in std::fs::read_dir(&dir)? {
            let entry = entry?;
            let meta = entry.metadata()?;
            if meta.is_dir() {
                stack.push(entry.path());
            } else if meta.is_file() {
                total += meta.len();
            }
        }
    }
    Ok(total)
}

/// Filesystem de origem com cancelamento cooperativo e progresso byte-a-byte,
/// implementado sobre a trait `xdvdfs::write::fs::Filesystem`.
struct CancelableStdFs {
    root: PathBuf,
    cancel: Arc<AtomicBool>,
    progress: Arc<SharedProgress>,
    total: u64,
}

impl Filesystem<BufWriter<File>, std::io::Error> for CancelableStdFs {
    fn read_dir(&mut self, path: &PathVec) -> std::io::Result<Vec<FileEntry>> {
        if self.cancel.load(Ordering::Relaxed) {
            return Err(std::io::Error::other("cancelado"));
        }
        let dir = path.as_path_buf(&self.root);
        let mut listing = Vec::new();
        for entry in std::fs::read_dir(&dir)? {
            let entry = entry?;
            let name = entry.file_name().to_string_lossy().to_string();
            let meta = entry.metadata()?;
            let (file_type, len) = if meta.is_dir() {
                (FileType::Directory, 0u64)
            } else {
                (FileType::File, meta.len())
            };
            listing.push(FileEntry { name, file_type, len });
        }
        Ok(listing)
    }

    fn copy_file_in(
        &mut self,
        src: &PathVec,
        dest: &mut BufWriter<File>,
        _offset: u64,
        _size: u64,
    ) -> std::io::Result<u64> {
        if self.cancel.load(Ordering::Relaxed) {
            return Err(std::io::Error::other("cancelado"));
        }
        let path = src.as_path_buf(&self.root);
        let display = src.as_string().trim_start_matches('/').to_string();
        self.progress.set_current_file(&display);
        let mut file = File::open(&path)?;
        let mut buffer = vec![0u8; READ_BUFFER];
        let mut copied: u64 = 0;
        loop {
            if self.cancel.load(Ordering::Relaxed) {
                return Err(std::io::Error::other("cancelado"));
            }
            let n = file.read(&mut buffer)?;
            if n == 0 {
                break;
            }
            dest.write_all(&buffer[..n])?;
            copied += n as u64;
            self.progress.add_processed(n as u64);
        }
        Ok(copied)
    }

    fn copy_file_buf(
        &mut self,
        _src: &PathVec,
        _buf: &mut [u8],
        _offset: u64,
    ) -> std::io::Result<u64> {
        Err(std::io::Error::new(
            std::io::ErrorKind::Unsupported,
            "copy_file_buf não é utilizado pelo XISO Tool",
        ))
    }
}

/// Cria uma imagem XISO a partir de `src_dir` (precisa conter default.xbe na raiz).
pub fn create_iso(
    src_dir: &Path,
    out_iso: &Path,
    cancel: Arc<AtomicBool>,
    progress: Arc<SharedProgress>,
) -> Result<(), XisoError> {
    if !src_dir.is_dir() {
        return Err(XisoError::new(
            ERR_NO_DEFAULT_XBE,
            Some("Pasta de origem não encontrada".into()),
        ));
    }
    if !src_dir.join("default.xbe").is_file() {
        return Err(XisoError::new(
            ERR_NO_DEFAULT_XBE,
            Some("A pasta do jogo precisa conter default.xbe na raiz".into()),
        ));
    }

    // Proteções básicas de caminho: destino dentro da origem é proibido.
    if let Ok(src_canon) = src_dir.canonicalize() {
        if let Some(parent) = out_iso.parent() {
            if let Ok(dst_canon) = parent.canonicalize() {
                if dst_canon.starts_with(&src_canon) {
                    return Err(XisoError::new(
                        ERR_IO,
                        Some("A pasta de destino da ISO não pode estar dentro da pasta do jogo".into()),
                    ));
                }
            }
        }
    }

    if let Some(parent) = out_iso.parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent)?;
        }
    }

    let total = estimate_source(src_dir)?;
    progress.set_total(total);

    let file = File::create(out_iso)?;
    let mut image = BufWriter::with_capacity(READ_BUFFER, file);

    let mut fs = CancelableStdFs {
        root: src_dir.to_path_buf(),
        cancel,
        progress,
        total,
    };

    let result = create_xdvdfs_image(&mut fs, &mut image, |_info| {});
    let flush_result = image.flush();

    match result {
        Ok(()) => {
            flush_result?;
            Ok(())
        }
        Err(e) => {
            let _ = std::fs::remove_file(out_iso);
            Err(e.into())
        }
    }
}
