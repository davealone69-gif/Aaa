# Animation

The current engine does not synthesize animation. Animation capability must be discovered from the imported GLB's actual scene data: skeletal clips require a skin and animation channels; facial animation requires morph targets/blendshapes. Assets without those structures must report `UNAVAILABLE`, not receive invented tracks.

A future animation service should expose clip discovery, selection, looping, speed, timeline position, and cancellation through the existing job architecture. No timeline UI is enabled in this revision because no end-to-end device-tested animation operation exists yet.
