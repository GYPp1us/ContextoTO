# 品牌资产生成

`build_brand.py` 从同一份图标几何与调色板生成：

- `app/src/main/res/` 中的自适应图标、单色主题图标、启动画面颜色与主题；
- `docs/brand/` 中的全部 SVG（图标、横幅、社交预览、特色图、调色板、字号层级、界面字形）。

规范说明见 [docs/ART_DIRECTION.md](../../docs/ART_DIRECTION.md)。

## 依赖

- Python 3.10+，`pip install fonttools`
- Tinos：直接读取仓库内 `app/src/main/res/font/`
- 中文轮廓需要 Noto Serif SC Regular / Bold 与 Noto Sans SC Regular（OFL，只在生成时使用，不进入仓库或 APK）：

```powershell
New-Item -ItemType Directory -Force build/brand-fonts | Out-Null
$base = 'https://github.com/notofonts/noto-cjk/raw/main'
Invoke-WebRequest "$base/Serif/SubsetOTF/SC/NotoSerifSC-Regular.otf" -OutFile build/brand-fonts/NotoSerifSC-Regular.otf
Invoke-WebRequest "$base/Serif/SubsetOTF/SC/NotoSerifSC-Bold.otf"    -OutFile build/brand-fonts/NotoSerifSC.otf
Invoke-WebRequest "$base/Sans/SubsetOTF/SC/NotoSansSC-Regular.otf"   -OutFile build/brand-fonts/NotoSansSC-Regular.otf
```

也可以用环境变量 `NOTO_CJK_DIR` 指向已有目录。

## 生成

```powershell
python tools/brand/build_brand.py
```

可传入另一个输出根目录，方便与仓库内容比对：`python tools/brand/build_brand.py E:\brand-check`。

PNG（`icon-play-512.png`、`social-preview.png`、`feature-graphic.png`）由对应 SVG 按原尺寸导出。SVG 里只有轮廓路径，没有字体引用，用浏览器、Inkscape 或 resvg 导出结果一致。
