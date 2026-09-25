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
# The source is tools/icon-source-padded.png, produced by tools/analyze-icon-ring.mjs.
# Do not go back to tools/icon-source.png: that one is cropped to the artwork's ink
# bounding box, so the outer ring of the tomato runs flush against the canvas edge and
# every launcher mask eats it. The padded source has the whole ring plus a proper
# margin, which is what keeps the icon inside the adaptive-icon safe zone.
#
# ASCII only: Windows PowerShell 5.1 decodes a BOM-less .ps1 as GBK.
#
# Usage: powershell -File tools/make-icon-assets.ps1

param(
    [string]$Source = "tools/icon-source-padded.png",
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

# Luminance at or above this counts as ink; below it is background.
$InkFloor = 24

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
        if ($l -ge $InkFloor) {
            if ($x -lt $minX) { $minX = $x }
            if ($x -gt $maxX) { $maxX = $x }
            if ($y -lt $minY) { $minY = $y }
            if ($y -gt $maxY) { $maxY = $y }
        }
    }
}

if ($maxX -lt 0) { throw "source image looks empty - no pixels at or above $InkFloor" }

$inkW = $maxX - $minX + 1
$inkH = $maxY - $minY + 1
$inkCx = ($minX + $maxX) / 2.0
$inkCy = ($minY + $maxY) / 2.0
Write-Host ("ink    x {0}..{1}  y {2}..{3}  ({4}x{5})" -f $minX, $maxX, $minY, $maxY, $inkW, $inkH)

# Fail loudly if the source is cropped again: ink touching the canvas edge is exactly
# the bug that made the outer ring disappear under the launcher mask.
if ($minX -le 1 -or $minY -le 1 -or $maxX -ge ($w - 2) -or $maxY -ge ($h - 2)) {
    throw ("source ink touches the canvas edge (x {0}..{1} of {2}, y {3}..{4} of {5}) - " +
        "regenerate it with: node tools/analyze-icon-ring.mjs") -f $minX, $maxX, $w, $minY, $maxY, $h
}

# ---------- 3. Emit a white-on-transparent silhouette ----------
#
# $coverage: how much of the output canvas the content should span.
#
# For the launcher this is about the SOURCE CANVAS, not the ink bounding box. The padded
# source is already framed so that the whole ring - including the corners of the rounded
# square, which stick out well past the ink box - lands inside the 66dp adaptive-icon safe
# zone. Fitting the ink box instead would magnify the ring by ~1.4x and blow straight
# through the safe zone, which is exactly how the ring got clipped before.
#   launcher foreground -> 1.00, the source canvas maps 1:1 onto the 432px canvas
#   notification mark   -> 0.96 of the ink box, it just needs to fill the 24dp box

function New-Silhouette {
    param(
        [int]$Size,
        [double]$Coverage,
        [string]$FileName,
        [switch]$FitSourceCanvas
    )

    $out = New-Object System.Drawing.Bitmap($Size, $Size,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $oRect = New-Object System.Drawing.Rectangle(0, 0, $Size, $Size)
    $oData = $out.LockBits($oRect, [System.Drawing.Imaging.ImageLockMode]::WriteOnly,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $oStride = $oData.Stride
    $oPixels = New-Object byte[] ($oStride * $Size)

    if ($FitSourceCanvas) {
        # 整张源画布按 $Coverage 缩放到输出画布，保持源图里已经调好的取景
        $scale = ($Size * $Coverage) / $w
        $srcCx = $w / 2.0
        $srcCy = $h / 2.0
    } else {
        $scale = ($Size * $Coverage) / [Math]::Max($inkW, $inkH)
        $srcCx = $inkCx
        $srcCy = $inkCy
    }
    $outCx = $Size / 2.0
    $outCy = $Size / 2.0

    for ($y = 0; $y -lt $Size; $y++) {
        $oRow = $y * $oStride
        for ($x = 0; $x -lt $Size; $x++) {
            # map output pixel back into source space
            $sx = [int][Math]::Round(($x + 0.5 - $outCx) / $scale + $srcCx - 0.5)
            $sy = [int][Math]::Round(($y + 0.5 - $outCy) / $scale + $srcCy - 0.5)
            $alpha = 0
            if ($sx -ge 0 -and $sx -lt $w -and $sy -ge 0 -and $sy -lt $h) {
                $l = $lum[$sy * $w + $sx]
                # The source is white ink on a black canvas, so the alpha channel has to
                # come from luminance. Pixels below $InkFloor are background and stay
                # fully transparent - without this the whole black canvas would be
                # painted as an opaque square on top of the icon's background layer.
                if ($l -ge $InkFloor) {
                    $alpha = $l
                }
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

    # ---- verify the ink lands inside the adaptive-icon safe zone ----
    $inkPx = [Math]::Max($inkW, $inkH) * $scale
    $dp = ($inkPx / $Size) * 108
    Write-Host ("       ink box {0:N0}px of {1} -> {2:N1}dp across (safe zone is 66dp; the ring's" -f
        $inkPx, $Size, $dp)
    Write-Host ("       rounded corners reach ~{0:N1}dp from the centre)" -f ($dp / 2 * 1.07))
}

New-Silhouette -Size 432 -Coverage 1.00 -FitSourceCanvas -FileName "ic_launcher_foreground.png"
New-Silhouette -Size 288 -Coverage 0.96 -FileName "ic_app_mark.png"
