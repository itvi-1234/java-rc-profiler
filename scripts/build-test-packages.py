#!/usr/bin/env python3
"""Build the binding packages the tests use from the extensions repository's conformance cases.

Every conformance case is a small, made-up extension (or a few that depend on each other) that
exercises one shape of the binding model: owned kinds, additive fields, transitive dependencies,
recursive types, unions, collections and maps, scoped domains, and so on. The profiler has no
knowledge of any real extension, so its tests use these.

For each extension of a case the script normalizes the binding model, runs the Java binding emitter,
and keeps the package's runtimeconditions resources: the manifest, the model, the extension
definition, and the file manifest that lists their digests. The generated Java source is written
beside them, only so a test author can see the API; the profiler never reads it.

usage: build-test-packages.py --extensions DIR --normalizer BIN --emitter BIN --output DIR

  --extensions  a checkout of github.com/runtimeconditions/extensions
  --normalizer  the rc-binding-model executable (go build ./cmd/rc-binding-model in normalizer/)
  --emitter     the Java emitter's launcher (gradle installDist in emitters/java/, then
                build/install/runtimeconditions-binding-emitter-java/bin/...)
  --output      a new directory; it gets <package>/runtimeconditions and <package>/source
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

CORE_ID = "https://runtimeconditions.io/schemas/profile/0.4.0/runtimeconditions.profile.schema.yaml"
CORE_VERSION = "0.4.0"
CORE_SEMANTIC_SHA256 = "ed447dccefd7507d905b0b6177b1386d8ee96a1d053b731939a9a7ae973d5af1"
RESOURCES = "runtimeconditions"
GROUP = "example.conformance"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]", "", re.sub(r"^[0-9]+-", "", name))


def emitter_digest(emitter: Path) -> str:
    return sha256(emitter.read_bytes())


def package_name(case: str, stem: str) -> str:
    return re.sub(r"^[0-9]+-", "", case) + ("" if stem == "root" else f"-{stem}")


def run(command: list[str]) -> None:
    done = subprocess.run(command, capture_output=True, text=True)
    if done.returncode != 0:
        raise RuntimeError(done.stderr.strip() or done.stdout.strip())


def normalize(args, case_dir: Path, extension_id: str, model: Path) -> dict:
    tooling = args.extensions / "tooling/extension-bindings"
    run([
        str(args.normalizer), "-root", extension_id, "-extension-root", str(case_dir),
        "-core-profile-id", CORE_ID, "-core-profile-version", CORE_VERSION,
        "-core-profile-semantic-sha256", CORE_SEMANTIC_SHA256,
        "-model-schema", str(tooling / "model/runtimeconditions.binding-model.schema.yaml"),
        "-semantic-schema", str(tooling / "model/runtimeconditions.extension-semantic.schema.yaml"),
        "-normalizer-sha256", "f" * 64, "-output", str(model),
    ])
    return yaml.safe_load(model.read_text(encoding="utf-8"))


def direct_providers(model: dict) -> list[str]:
    """The direct dependencies of the root, and the owners of declarations it imports."""
    root = model["rootExtension"]["id"]
    found = []
    for entry in model["extensions"]:
        if entry["id"] == root:
            found += entry.get("dependencies") or []
    for item in (model.get("vocabulary") or {}).get("importedDeclarations") or []:
        if item["owner"] not in found:
            found.append(item["owner"])
    return found


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for name in ("extensions", "normalizer", "emitter", "output"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    args = parser.parse_args()
    cases = args.extensions / "tooling/extension-bindings/model/conformance/cases"
    args.output.mkdir(parents=True, exist_ok=False)
    emitter_sha = emitter_digest(args.emitter)
    built = 0
    skipped = []
    with tempfile.TemporaryDirectory() as work_name:
        work = Path(work_name)
        for case_dir in sorted(path for path in cases.iterdir() if path.is_dir()):
            definitions = {}
            for file in sorted(case_dir.glob("*.yaml")):
                definitions[file.stem] = (file, yaml.safe_load(file.read_text(encoding="utf-8"))["metadata"]["id"])
            models = {}
            try:
                for stem, (file, extension_id) in definitions.items():
                    models[stem] = normalize(args, case_dir, extension_id, work / f"{case_dir.name}-{stem}.model.yaml")
            except RuntimeError as error:
                skipped.append(f"{case_dir.name}: {str(error).splitlines()[0]}")
                continue
            names = {definitions[stem][1]: package_name(case_dir.name, stem) for stem in definitions}
            for stem, (file, extension_id) in definitions.items():
                name = package_name(case_dir.name, stem)
                model = models[stem]
                dependencies = []
                for provider in direct_providers(model):
                    other = names[provider]
                    dependencies.append({
                        "extension": provider, "groupId": GROUP, "artifactId": other,
                        "javaPackage": f"{GROUP}.{slug(other)}", "version": "1.0.0",
                    })
                target = {
                    "apiVersion": "runtimeconditions.io/java-package-target/v1alpha1",
                    "kind": "RuntimeConditionsJavaPackageTarget",
                    "packageKey": re.sub(r"[^a-z0-9]+", "-", name).strip("-"),
                    "rootExtension": extension_id, "groupId": GROUP, "artifactId": name,
                    "javaPackage": f"{GROUP}.{slug(name)}", "version": "1.0.0",
                    "sourceDirectory": f"bindings/{re.sub(r'[^a-z0-9]+', '-', name).strip('-')}/java",
                    "minimumJavaVersion": "17", "publicationMode": "github-tag",
                    "repositoryUrl": "https://github.com/runtimeconditions/extensions",
                    "dependencies": dependencies, "emitterSha256": emitter_sha,
                }
                target_file = work / f"{name}.target.yaml"
                target_file.write_text(yaml.safe_dump(target, sort_keys=False), encoding="utf-8")
                package = args.output / name
                emitted = work / name
                common = ["--model", str(work / f"{case_dir.name}-{stem}.model.yaml"), "--package-config", str(target_file)]
                try:
                    run([str(args.emitter), "emit", *common, "--output", str(emitted)])
                    run([str(args.emitter), "verify", *common, "--input", str(emitted)])
                except RuntimeError as error:
                    skipped.append(f"{name}: {str(error).splitlines()[0]}")
                    continue
                resources = package / RESOURCES
                resources.mkdir(parents=True)
                shutil.copyfile(emitted / "src/main/resources/runtimeconditions/runtimeconditions.bindings.yaml",
                                resources / "runtimeconditions.bindings.yaml")
                shutil.copyfile(work / f"{case_dir.name}-{stem}.model.yaml", resources / "runtimeconditions.binding-model.yaml")
                shutil.copyfile(file, resources / "runtimeconditions.extension.yaml")
                lines = ["apiVersion: runtimeconditions.io/file-manifest/v1alpha1", "kind: RuntimeConditionsFileManifest", "files:"]
                for resource in sorted(path.name for path in resources.iterdir()):
                    lines.append(f"    {resource}: {sha256((resources / resource).read_bytes())}")
                (resources / "runtimeconditions.file-manifest.yaml").write_text("\n".join(lines) + "\n", encoding="utf-8")
                if (emitted / "src/main/java").is_dir():
                    shutil.copytree(emitted / "src/main/java", package / "source")
                built += 1
    print(json.dumps({"built": built, "skipped": skipped}, indent=2))
    if not built:
        sys.exit(1)


if __name__ == "__main__":
    main()
