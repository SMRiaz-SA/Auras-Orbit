[Setup]
#include "version.iss"
AppId={{C626E83F-8C3A-4D78-B5B3-FA19FE223E0C}}
AppName=Auras Orbit
AppVersion={#AppVersion}
AppPublisher=Auras Prime Dynamics
AppPublisherURL=https://github.com/errorcode26/CS3-desktop-client-unofficial
AppSupportURL=https://github.com/errorcode26/CS3-desktop-client-unofficial
AppUpdatesURL=https://github.com/errorcode26/CS3-desktop-client-unofficial
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
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
TimeStampsInUTC=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
; Copy all files and folders from the AppImage output
Source: "..\desktop-app\build\compose\binaries\main\app\Auras-Orbit\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Auras Orbit"; Filename: "{app}\Auras-Orbit.exe"
Name: "{group}\{cm:UninstallProgram,Auras Orbit}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Auras Orbit"; Filename: "{app}\Auras-Orbit.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Auras-Orbit.exe"; Description: "{cm:LaunchProgram,Auras Orbit}"; Flags: nowait postinstall skipifsilent runasoriginaluser
