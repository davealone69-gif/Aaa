# Architecture

`app` depends on `avatar-sdk`; host applications need only the SDK module or published AAR.

```text
Host app -> AvatarEngine -> AvatarLibrary -> GLB validator -> app-private storage
                         -> AvatarView -> Filament Engine -> real-time GLB rendering
                         -> Provider registry (future local/worker providers)
                         -> ResourceGovernor
```

The validator is a trust boundary: an asset is not returned from import or load until its GLB container is structurally valid. Rendering does not use a pre-recorded image or hard-coded avatar.

## Planned modules

Generation, animation, model installation, Room persistence, and import/export adapters should be added as separate modules after their real implementations and licenses are selected. They must return explicit unavailable/unsupported states rather than simulated completion.
