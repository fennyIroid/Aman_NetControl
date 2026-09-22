# Bundling a content classifier

The video blurring pipeline judges pictures with whatever sits in the `PurposeTrainedLabeler`
slot, and falls back to ML Kit's general image labeller when that slot is empty. It is empty in
this build. `ModelIdentity.purposeTrained` is therefore `false`, every screen that surfaces a blur
verdict says so, and nothing presents a verdict as a safety determination — rule 11.

## What ML Kit can and cannot do here

The bundled labeller **cannot be trained, fine-tuned or corrected.** It is a fixed network
returning 430 general labels and "explicit" is not one of them. `SafetyLabelRisk` reads swimwear,
bathing, lingerie and adult settings out of its vocabulary and infers the rest. That is a proxy,
it is recorded as one, and no amount of re-weighting that table turns it into a content
classifier.

What ML Kit does support is running **a classifier of your own** on the same on-device runtime.
That is the accurate path, and the loading code for it is already here.

## Dropping a model in

1. Put an image-classification TFLite model at `app/src/main/assets/nsfw.tflite`.
   It must carry [TFLite Model Metadata](https://www.tensorflow.org/lite/models/convert/metadata),
   or ML Kit cannot read the class names back and the labels arrive as `"0"`, `"1"`, `"2"`.
   `NsfwClasses` recognises nothing in that case and the build stays on the proxy rather than
   acting on names it cannot read.
2. Check the model's class names against the table in `NsfwClasses`. The usual open classifiers
   emit `drawings`, `hentai`, `neutral`, `porn`, `sexy`, which the table already covers. A model
   with different names needs a line adding there, not a rewrite.
3. Nothing else changes. `identity` starts reporting the bundled model, `purposeTrained` becomes
   `true`, the screens follow, and the labeller and the skin heuristic demote themselves to
   corroboration that can raise a verdict and never clear one
   (`ImageSafetyClassifier.PROXY_SHARE_WHEN_TRAINED`).
4. Re-derive `ImageSafetyClassifier.UNSAFE_THRESHOLD` and the `ProtectionLevel` thresholds against
   the new model. They were tuned against a proxy and mean something different once a real
   classifier is producing the scores.

Choosing the model is a licensing decision as much as an accuracy one — the model's licence
travels with the APK — so no weights are committed here.

## Why not a hosted vision model

This feature looks at every frame of everything the child watches. Sending that to a server is not
a better classifier, it is a different product with a different privacy promise, and the one
written on `ContentSafetyModel` would have to be withdrawn and disclosed. It also does not fit the
budget: a cover has to be up inside the time the frame is on screen — the number `BlurLatency`
exists to report — and a network round trip per frame is one to two orders of magnitude outside
it.

A hosted model has a real place in this product, and it is not the covering loop: reviewing a
sample of what was flagged, and tuning or distilling what gets bundled. Both are offline, both are
over material a parent has agreed to share, and neither one is on the path between a frame
arriving and a cover going up.
