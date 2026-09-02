# App icon

`app-icon.svg` is the source of truth for the launcher icon. `app-icon-foreground.svg` and
`app-icon-monochrome.svg` are generated from it (the background rounded-rect stripped out, since
that's now a separate solid-color adaptive icon layer; monochrome has every fill flattened to
white for the themed-icon variant) -- don't hand-edit those two, regenerate them instead:

```bash
python3 -c "
import re
with open('app-icon.svg') as f:
    content = f.read()
content = re.sub(r'<metadata>.*?</metadata>', '', content, flags=re.DOTALL)
content = re.sub(r'\s*<rect x=\"0\" y=\"0\" width=\"240\" height=\"240\" rx=\"54\" fill=\"#10102B\"></rect>', '', content)
with open('app-icon-foreground.svg', 'w') as f:
    f.write(content)
"
sed -E 's/fill="#[0-9A-Fa-f]{6}"/fill="#FFFFFF"/g' app-icon-foreground.svg > app-icon-monochrome.svg
```

Then re-rasterize into the app's adaptive icon mipmaps (requires `inkscape`):

```bash
declare -A SIZES=( [mdpi]=108 [hdpi]=162 [xhdpi]=216 [xxhdpi]=324 [xxxhdpi]=432 )
for density in "${!SIZES[@]}"; do
  size=${SIZES[$density]}
  inkscape app-icon-foreground.svg --export-type=png -w "$size" -h "$size" \
    --export-filename="../app/src/main/res/mipmap-$density/ic_launcher_foreground.png"
  inkscape app-icon-monochrome.svg --export-type=png -w "$size" -h "$size" \
    --export-filename="../app/src/main/res/mipmap-$density/ic_launcher_monochrome.png"
done
```

## Why no legacy (pre-adaptive-icon) raster fallback

`minSdk` is 27 (see `app/build.gradle.kts`), well past the API 26 adaptive-icon cutoff, so every
supported device resolves `mipmap-anydpi/ic_launcher.xml` -- the old per-density flat
`ic_launcher.webp`/`ic_launcher_round.webp` files the default Android Studio template ships are
dead weight here and were deleted rather than regenerated. The background layer is a plain solid
color (`app/src/main/res/drawable/ic_launcher_background.xml`), so it doesn't need a source image
at all.
