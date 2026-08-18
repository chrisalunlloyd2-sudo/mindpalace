Add-Type -AssemblyName System.Windows.Forms
foreach ($s in [System.Windows.Forms.Screen]::AllScreens) {
    Write-Output ("Screen: " + $s.Bounds.Width + "x" + $s.Bounds.Height + " working: " + $s.WorkingArea.Width + "x" + $s.WorkingArea.Height)
}
