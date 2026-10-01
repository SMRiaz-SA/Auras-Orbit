[Setup]
#include "..\desktop-app\build\generated\installer\version.iss"
AppId={{C626E83F-8C3A-4D78-B5B3-FA19FE223E0C}}
AppName=Auras Orbit
AppVersion={#AppVersion}
AppPublisher=Auras Prime Dynamics
AppPublisherURL=https://github.com/SMRiaz-SA/Auras-Orbit
AppSupportURL=https://github.com/SMRiaz-SA/Auras-Orbit/issues
AppUpdatesURL=https://github.com/SMRiaz-SA/Auras-Orbit/releases
DefaultDirName={autopf}\Auras Orbit
DefaultGroupName=Auras Orbit
AllowNoIcons=yes
SetupIconFile=..\desktop-app\src\main\resources\app_icon.ico
UninstallDisplayIcon={app}\Auras-Orbit.exe
; Output directory for the compiled installer
OutputDir=..\desktop-app\build\outputs
OutputBaseFilename=Auras-Orbit-Setup
Compression=lzma2/ultra64
SolidCompression=yes
LZMAUseSeparateProcess=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
TimeStampsInUTC=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
; Copy all files and folders from the AppImage output
Source: "..\desktop-app\build\compose\binaries\main\app\Auras-Orbit\*"; DestDir: "{app}"; Excludes: "AurasOrbitData\*,data\*,shared_prefs\*,profiles\*,Extensions\*,logs\*,downloads\*,settings.json,auth_tokens*,tracker_credentials*,*.db,*.db-shm,*.db-wal,*.sqlite,*.sqlite3"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Auras Orbit"; Filename: "{app}\Auras-Orbit.exe"
Name: "{group}\{cm:UninstallProgram,Auras Orbit}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Auras Orbit"; Filename: "{app}\Auras-Orbit.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Auras-Orbit.exe"; Description: "{cm:LaunchProgram,Auras Orbit}"; Flags: nowait postinstall skipifsilent runasoriginaluser
