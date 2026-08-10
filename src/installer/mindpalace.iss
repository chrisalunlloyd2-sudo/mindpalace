; MindPalace Windows Installer
; Inno Setup Script — generates mindpalace-setup.exe
; 
; Build: iscc mindpalace.iss

#define MyAppName "MindPalace"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "AEGIS System"
#define MyAppURL "https://github.com/chrisalunlloyd2-sudo/mindpalace"
#define MyAppExeName "mindpalace.exe"

[Setup]
AppId={{B4D9E8F1-2A3C-4D5E-6F7A-8B9C0D1E2F3A}}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={autopf}\MindPalace
DefaultGroupName=MindPalace
AllowNoIcons=yes
LicenseFile=..\LICENSE.txt
OutputDir=..\target\installer
OutputBaseFilename=mindpalace-setup-{#MyAppVersion}
Compression=lzma
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "..\target\mindpalace-{#MyAppVersion}.jar"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\target\dependency\*"; DestDir: "{app}\lib"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent

[Code]
var
  PatPage: TInputQueryWizardPage;

procedure InitializeWizard;
begin
  PatPage := CreateInputQueryPage(wpSelectDir,
    'GitHub Personal Access Token', 'Enter your GitHub PAT to auto-populate repos',
    'Your token is stored locally and never sent anywhere except GitHub API.' + #13#10 +
    'You can skip this and enter it later in Settings.');
  PatPage.Add('GitHub PAT:', True);
  PatPage.Values[0] := '';
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if CurPageID = PatPage.ID then begin
    // Save PAT to config file
    if PatPage.Values[0] <> '' then begin
      SaveStringToFile(ExpandConstant('{app}\config\pat.txt'), PatPage.Values[0], False);
    end;
  end;
end;
