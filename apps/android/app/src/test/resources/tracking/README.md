# Grayscale tracking fixture

`camera-192x192.gray` is 192×192 unsigned 8-bit grayscale, row-major, without a header.

- Photographer: Lav Varshney. The scikit-image documentation identifies this replacement camera photograph as CC0.
- Source documentation: https://scikit-image.org/docs/stable/api/skimage.data.html#skimage.data.camera
- Pinned image: https://raw.githubusercontent.com/scikit-image/scikit-image/v0.25.2/skimage/data/camera.png
- Source PNG SHA256: b0793d2adda0fa6ae899c03989482bff9a42d3d5690fc7e3648f2795d730c23a
- Fixture SHA256: 992eadf98d2e36f43a29a9d2ffd0a23833da32e3a004600875f31a01585862d2
- Conversion: Pillow 12.1.1, grayscale conversion followed by 192×192 bilinear resize and raw bytes export. No crop.

The fixture supports controlled pixel-correspondence tests; it is not evidence of field tracking accuracy.
