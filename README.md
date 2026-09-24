# News-faces

A single-file web app that turns the latest news photos into a colour-sorted gallery and a live face mosaic.

- **Gallery**: fetches image-bearing stories from 13 news feeds (Guardian, BBC, NYT, CBC, France 24, NBC, ABC Australia), analyses each photo's colour, and sorts by time, hue, lightness, vividness, warmth or source. Pinch or ctrl/⌘+scroll to zoom without limit, from a dot mosaic to a single photo larger than the screen.
- **Face**: your webcam (or an uploaded photo) is rebuilt live from the news photos. Each cell shows the photo whose colour is closest to that part of your face. Hue, saturation, brightness, contrast and tint shift the *target*, so different photos get chosen; the photos themselves are never altered. Detects every face in frame and lays the heads out in a grid, with an organic head outline from person segmentation.
- **Record**: capture the live mosaic and export an MP4 straight from the browser.
- Foldable side menu with sliders for every parameter.

## Run

Open `index.html` directly, or for reliable feed loading run the tiny local proxy:

```bash
python3 serve.py
```

then open http://localhost:8765. No build step, no API keys. Face detection and segmentation use MediaPipe models loaded from CDN on first use.
