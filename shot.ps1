Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$b = New-Object System.Drawing.Bitmap([System.Windows.Forms.Screen]::PrimaryScreen.Bounds.Width, [System.Windows.Forms.Screen]::PrimaryScreen.Bounds.Height)
$g = [System.Drawing.Graphics]::FromImage($b)
$g.CopyFromScreen(0,0,0,0,$b.Size)
$b.Save("C:\Users\viper\AIGEN_SYS\repos\mindpalace\shot.png", [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $b.Dispose()
Write-Output "saved"
