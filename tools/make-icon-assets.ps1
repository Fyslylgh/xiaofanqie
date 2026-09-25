# Derive icon assets from a white-on-black source PNG.
#
# Why not use the source image directly for every icon slot:
#   - Launcher icon: fine, it is a normal image. We key out the black so it can sit
#     on the black background layer of the adaptive icon.
#   - Notification small icon and Quick Settings tile icon: the system uses ONLY the
#     alpha channel and tints it. A white-on-black image has a fully opaque alpha
#     everywhere, so it would render as a solid block. Those slots need a
#     white-on-transparent silhouette - which is exactly what this script produces,
#     so the shape still comes from the source image rather than being hand-drawn.
#
# ASCII only: Windows PowerShell 5.1 decodes a BOM-less .ps1 as GBK.
#
# Usage: powershell -File .toolchain/make-icon-assets.ps1

param(
    [string]$Source = "tools/icon-source.png",
    [string]$OutDir = "app/src/main/res/drawable-nodpi"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$srcPath = Join-Path $root $Source
$outPath = Join-Path $root $OutDir
New-Item -ItemType Directory -Path $outPath -Force | Out-Null

# ---------- 1. Read the source into a byte array ----------

$bmp = New-Object System.Drawing.Bitmap($srcPath)
$w = $bmp.Width
$h = $bmp.Height
$rect = New-Object System.Drawing.Rectangle(0, 0, $w, $h)
$data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
    [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$stride = $data.Stride
$pixels = New-Object byte[] ($stride * $h)
[System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $pixels, 0, $pixels.Length)
$bmp.UnlockBits($data)
$bmp.Dispose()

Write-Host ("source {0}x{1}, stride {2}" -f $w, $h, $stride)

# ---------- 2. Ink bounding box ----------

$minX = $w; $maxX = -1; $minY = $h; $maxY = -1
$lum = New-Object int[] ($w * $h)

for ($y = 0; $y -lt $h; $y++) {
    $row = $y * $stride
    for ($x = 0; $x -lt $w; $x++) {
        $i = $row + $x * 4
        # BGRA in memory
        $b = $pixels[$i]; $g = $pixels[$i + 1]; $r = $pixels[$i + 2]
        $l = [int](0.2126 * $r + 0.7152 * $g + 0.0722 * $b)
        $lum[$y * $w + $x] = $l
        if ($l -gt 24) {
            if ($x -lt $minX) { $minX = $x }
            if ($x -gt $maxX) { $maxX = $x }
            if ($y -lt $minY) { $minY = $y }
            if ($y -gt $maxY) { $maxY = $y }
        }
    }
}

if ($maxX -lt 0) { throw "source image looks empty - no pixels brighter than 24" }

$inkW = $maxX - $minX + 1
$inkH = $maxY - $minY + 1
$inkCx = ($minX + $maxX) / 2.0
$inkCy = ($minY + $maxY) / 2.0
Write-Host ("ink    x {0}..{1}  y {2}..{3}  ({4}x{5})" -f $minX, $maxX, $minY, $maxY, $inkW, $inkH)

# ---------- 3. Emit a white-on-transparent silhouette ----------
#
# $coverage: fraction of the output canvas the ink should occupy.
#   launcher foreground -> 0.70, so the shape stays inside the adaptive icon
#                          safe zone (the inner 72 of 108 units) under any mask
#   notification mark   -> 0.94, nearly filling the 24dp box

function New-Silhouette {
    param([int]$Size, [double]$Coverage, [string]$FileName)

    $out = New-Object System.Drawing.Bitmap($Size, $Size,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $oRect = New-Object System.Drawing.Rectangle(0, 0, $Size, $Size)
    $oData = $out.LockBits($oRect, [System.Drawing.Imaging.ImageLockMode]::WriteOnly,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $oStride = $oData.Stride
    $oPixels = New-Object byte[] ($oStride * $Size)

    $target = $Size * $Coverage
    $scale = $target / [Math]::Max($inkW, $inkH)
    $outCx = $Size / 2.0
    $outCy = $Size / 2.0

    for ($y = 0; $y -lt $Size; $y++) {
        $oRow = $y * $oStride
        for ($x = 0; $x -lt $Size; $x++) {
            # map output pixel back into source space
            $sx = [int][Math]::Round(($x + 0.5 - $outCx) / $scale + $inkCx - 0.5)
            $sy = [int][Math]::Round(($y + 0.5 - $outCy) / $scale + $inkCy - 0.5)
            $alpha = 0
            if ($sx -ge 0 -and $sx -lt $w -and $sy -ge 0 -and $sy -lt $h) {
                $alpha = $lum[$sy * $w + $sx]
            }
            $o = $oRow + $x * 4
            $oPixels[$o] = 255      # B
            $oPixels[$o + 1] = 255  # G
            $oPixels[$o + 2] = 255  # R
            $oPixels[$o + 3] = [byte]$alpha
        }
    }

    [System.Runtime.InteropServices.Marshal]::Copy($oPixels, 0, $oData.Scan0, $oPixels.Length)
    $out.UnlockBits($oData)

    $file = Join-Path $outPath $FileName
    $out.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
    $out.Dispose()
    Write-Host ("wrote  {0}  {1}x{1}  coverage {2:P0}  {3} bytes" -f
        $FileName, $Size, $Coverage, (Get-Item $file).Length)
}

New-Silhouette -Size 432 -Coverage 0.70 -FileName "ic_launcher_foreground.png"
New-Silhouette -Size 288 -Coverage 0.94 -FileName "ic_app_mark.png"
