#!/usr/bin/env python3
"""Assemble the runtimeconditions resources of a generated Java binding package.

The Java binding emitter writes runtimeconditions.bindings.yaml. A package also carries the binding
model and the root extension definition, and a file manifest that lists the digests of all three. This
script copies them next to the manifest and writes the file manifest, as the packaging step does.

usage: assemble-binding-package.py EMITTER_OUTPUT_DIR BINDING_MODEL EXTENSION_DEFINITION OUTPUT_DIR
"""
import hashlib
import shutil
import sys
from pathlib import Path


def main(argv):
    if len(argv) != 5:
        sys.exit(__doc__)
    emitted, model, extension, output = (Path(item) for item in argv[1:])
    target = output / "runtimeconditions"
    target.mkdir(parents=True, exist_ok=False)
    sources = {
        "runtimeconditions.bindings.yaml": emitted / "src/main/resources/runtimeconditions/runtimeconditions.bindings.yaml",
        "runtimeconditions.binding-model.yaml": model,
        "runtimeconditions.extension.yaml": extension,
    }
    lines = ["apiVersion: runtimeconditions.io/file-manifest/v1alpha1", "kind: RuntimeConditionsFileManifest", "files:"]
    for name in sorted(sources):
        shutil.copyfile(sources[name], target / name)
        lines.append(f"    {name}: {hashlib.sha256((target / name).read_bytes()).hexdigest()}")
    (target / "runtimeconditions.file-manifest.yaml").write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main(sys.argv)
