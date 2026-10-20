# Move the Android emulator window back onto the visible screen.
#
# Why this exists:
#   The AVD is 1080x2400 px, but this machine's desktop is 1280x720.
#   The emulator centers the window against the *scaled* desktop size,
#   which puts the top of the window at a negative Y (-730 in practice).
#   The window is there, it is just entirely above the visible area.
#
# Usage: powershell -ExecutionPolicy Bypass -File fix-emulator-window.ps1
#
# Keep this file ASCII-only: it is launched from a .bat with chcp 65001,
# and non-ASCII text in here has bitten us before.

$sig = @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public class EmuWin {
    public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll")]
    public static extern bool EnumWindows(EnumProc lpEnumFunc, IntPtr lParam);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern int GetWindowTextW(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

    [DllImport("user32.dll")]
    public static extern bool IsWindowVisible(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    [DllImport("user32.dll")]
    public static extern bool MoveWindow(IntPtr hWnd, int X, int Y, int nWidth, int nHeight, bool bRepaint);

    [DllImport("user32.dll")]
    public static extern int GetSystemMetrics(int nIndex);

    public struct RECT { public int Left, Top, Right, Bottom; }
}
'@

Add-Type -TypeDefinition $sig -Language CSharp

$screenW = [EmuWin]::GetSystemMetrics(0)
$screenH = [EmuWin]::GetSystemMetrics(1)

# The window may not exist yet when this runs right after boot_completed.
# Retry for a while instead of failing once and leaving the user stuck.
$script:target = [IntPtr]::Zero
$attempt = 0
$maxAttempts = 20

while ($attempt -lt $maxAttempts -and $script:target -eq [IntPtr]::Zero) {
    $attempt++

    $callback = [EmuWin+EnumProc]{
        param($hWnd, $lParam)

        if (-not [EmuWin]::IsWindowVisible($hWnd)) { return $true }

        $sb = New-Object System.Text.StringBuilder 512
        [void][EmuWin]::GetWindowTextW($hWnd, $sb, 512)
        $title = $sb.ToString()

        if ($title -like "*Android Emulator*") {
            $script:target = $hWnd
            return $false
        }
        return $true
    }

    [void][EmuWin]::EnumWindows($callback, [IntPtr]::Zero)

    if ($script:target -eq [IntPtr]::Zero) {
        Start-Sleep -Milliseconds 500
    }
}

if ($script:target -eq [IntPtr]::Zero) {
    Write-Host "  [!] Emulator window not found after $maxAttempts tries."
    exit 1
}

$rect = New-Object EmuWin+RECT
[void][EmuWin]::GetWindowRect($script:target, [ref]$rect)

$oldW = $rect.Right - $rect.Left
$oldH = $rect.Bottom - $rect.Top

# Leave room for the taskbar; keep the aspect ratio.
$maxH = $screenH - 40
if ($oldH -gt $maxH) {
    $newH = $maxH
    $newW = [int]($oldW * $maxH / $oldH)
} else {
    $newH = $oldH
    $newW = $oldW
}

[void][EmuWin]::MoveWindow($script:target, 8, 4, $newW, $newH, $true)

Write-Host "  Desktop : ${screenW}x${screenH}"
Write-Host "  Was     : ${oldW}x${oldH} at ($($rect.Left),$($rect.Top))"
Write-Host "  Now     : ${newW}x${newH} at (8,4)"
Write-Host "  [+] Emulator window is back on screen."
