; MindPalace.iss — warm, welcoming Windows installer (Inno Setup 6).
;
; Builds on top of the jpackage app-image (installer/MindPalace/). Adds:
;   - a branded welcome page (warm gradient + title)
;   - a file-location chooser (defaults to {autopf}\MindPalace)
;   - an accessory picker: optional Ollama install + the 4 MindPalace models
;   - Start Menu shortcut + desktop shortcut + uninstaller
;   - a post-install "launch now" checkbox
;
; Build:  ISCC.exe MindPalace.iss   (from the repo root)

#define MyAppName "MindPalace"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "AIGEN_SYS"
#define MyAppExeName "MindPalace.exe"
#define MyAppSource "installer\MindPalace"

[Setup]
AppId={{8F3A1C2E-4B5D-4E6F-9A7B-1C2D3E4F5A6B}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputDir=installer
OutputBaseFilename=MindPalace-Setup-{#MyAppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
; Warm, welcoming look
WizardImageFile=installer\wizard.bmp
SetupIconFile=installer\MindPalace\MindPalace.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
PrivilegesRequired=lowest
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Additional icons:"
Name: "ollama"; Description: "Install &Ollama (local model runtime)"; GroupDescription: "Accessories:"; Flags: unchecked
Name: "models"; Description: "Download the 4 MindPalace &models (~4.8 GB)"; GroupDescription: "Accessories:"; Flags: unchecked

[Files]
Source: "{#MyAppSource}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName} now"; Flags: nowait postinstall skipifsilent

[Code]
// Post-install: run the accessory tasks (Ollama + models) if the user opted in.
procedure CurStepChanged(CurStep: TSetupStep);
var
  ResultCode: Integer;
begin
  if CurStep = ssPostInstall then
  begin
    if WizardIsTaskSelected('ollama') then
      Exec('winget', 'install --id Ollama.Ollama --silent --accept-package-agreements --accept-source-agreements', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);

    if WizardIsTaskSelected('models') then
    begin
      Exec('cmd.exe', '/c ollama pull llama3.2:1b', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
      Exec('cmd.exe', '/c ollama pull qwen2.5:0.5b', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
      Exec('cmd.exe', '/c ollama pull llama3.2:3b', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
      Exec('cmd.exe', '/c ollama pull nomic-embed-text', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    end;
  end;
end;
