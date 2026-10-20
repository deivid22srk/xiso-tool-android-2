//! Códigos de erro trocados entre o núcleo Rust e o app Android via JNI.
//!
//! O Kotlin mapeia cada código para uma mensagem em português (strings.xml/literals).

pub const ERR_OK: i32 = 0;
pub const ERR_INVALID_ISO: i32 = 1;
pub const ERR_NO_DEFAULT_XBE: i32 = 2;
pub const ERR_IO: i32 = 3;
pub const ERR_CANCELLED: i32 = 4;
pub const ERR_NO_SPACE: i32 = 5;
pub const ERR_SOURCE_MISSING: i32 = 6;
pub const ERR_UNKNOWN: i32 = 7;

/// Erro de alto nível com código estável para a UI e detalhe opcional (texto técnico).
pub struct XisoError {
    pub code: i32,
    pub detail: Option<String>,
}

impl XisoError {
    pub fn new(code: i32, detail: Option<String>) -> Self {
        XisoError { code, detail }
    }
}

pub fn cancelled_error() -> XisoError {
    XisoError::new(ERR_CANCELLED, Some("Operação cancelada pelo usuário".into()))
}

impl From<std::io::Error> for XisoError {
    fn from(e: std::io::Error) -> Self {
        // ENOSPC (28) = espaço insuficiente; StorageFull é o mapeamento moderno.
        let code = if e.kind() == std::io::ErrorKind::StorageFull
            || e.raw_os_error() == Some(28)
        {
            ERR_NO_SPACE
        } else if e.kind() == std::io::ErrorKind::NotFound {
            ERR_SOURCE_MISSING
        } else {
            ERR_IO
        };
        XisoError::new(code, Some(e.to_string()))
    }
}

impl From<xdvdfs::util::Error<std::io::Error>> for XisoError {
    fn from(e: xdvdfs::util::Error<std::io::Error>) -> Self {
        use xdvdfs::util::Error as U;
        match e {
            U::IOError(io) => io.into(),
            U::InvalidVolume => XisoError::new(
                ERR_INVALID_ISO,
                Some("Não é uma imagem XISO/XDVDFS válida".into()),
            ),
            U::NameTooLong => XisoError::new(ERR_IO, Some("Nome de arquivo muito longo para o formato XDVDFS".into())),
            U::InvalidFileName => XisoError::new(ERR_IO, Some("Nome de arquivo inválido no formato XDVDFS".into())),
            U::TooManyDirectoryEntries => XisoError::new(ERR_IO, Some("Diretório com entradas demais para o formato XDVDFS".into())),
            U::FileTooLarge => XisoError::new(ERR_IO, Some("Arquivo maior que o limite do formato XDVDFS (4 GiB)".into())),
            other => XisoError::new(ERR_IO, Some(other.to_string())),
        }
    }
}
