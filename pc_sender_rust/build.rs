fn main() {
    if std::env::var("CARGO_CFG_TARGET_OS").unwrap() == "windows" {
        let mut res = winres::WindowsResource::new();
        res.set_icon("icon.ico");
        res.set("ProductName", "SoundMirror");
        res.set("FileDescription", "SoundMirror Windows Sender");
        res.set("FileVersion", env!("CARGO_PKG_VERSION"));
        res.set("ProductVersion", env!("CARGO_PKG_VERSION"));
        let version = (1u64 << 48) | (1u64 << 32);
        res.set_version_info(winres::VersionInfo::FILEVERSION, version);
        res.set_version_info(winres::VersionInfo::PRODUCTVERSION, version);
        res.compile().unwrap();
    }
}
