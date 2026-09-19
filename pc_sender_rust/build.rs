fn main() {
    if std::env::var("CARGO_CFG_TARGET_OS").unwrap() == "windows" {
        let mut res = winres::WindowsResource::new();
        res.set_icon("icon.ico");
        res.set("ProductName", "SoundMirror");
        res.set("FileDescription", "SoundMirror Windows Sender");
        res.set("FileVersion", env!("CARGO_PKG_VERSION"));
        res.set("ProductVersion", env!("CARGO_PKG_VERSION"));
        let major: u64 = env!("CARGO_PKG_VERSION_MAJOR").parse().unwrap();
        let minor: u64 = env!("CARGO_PKG_VERSION_MINOR").parse().unwrap();
        let patch: u64 = env!("CARGO_PKG_VERSION_PATCH").parse().unwrap();
        let version = (major << 48) | (minor << 32) | (patch << 16);
        res.set_version_info(winres::VersionInfo::FILEVERSION, version);
        res.set_version_info(winres::VersionInfo::PRODUCTVERSION, version);
        res.compile().unwrap();
    }
}
