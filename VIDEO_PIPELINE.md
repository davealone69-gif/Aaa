# Video Pipeline

The intended pipeline is GLB avatar → real animation data → camera/lighting → rendered frames → audio → validated MP4. The current repository stops before video generation: no video backend, renderer/exporter, media validator, or physical Android playback test is installed or verified.

The Worker now exposes a machine-readable video capability state. When no Wan 2.2 or LTX-2 backend is installed, video requests return `VIDEO_BACKEND_UNAVAILABLE` and never create a fake job or placeholder media file.
