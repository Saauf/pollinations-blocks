from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import urllib.request
import zipfile
import xml.etree.ElementTree as E

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--jdk", default=os.environ.get("JAVA_HOME"), help="JDK 8 home directory")
parser.add_argument("--tools", type=Path, default=root / ".tools")
args = parser.parse_args()
if not args.jdk:
    parser.error("Pass --jdk /path/to/jdk8 or set JAVA_HOME")
jdk = Path(args.jdk)
tools = args.tools.resolve()
tools.mkdir(parents=True, exist_ok=True)
artifacts = {
    "ant.jar": ("org/apache/ant/ant/1.10.15/ant-1.10.15.jar", "763acda4a69588c9ea8817a952851ff0c2fc4bffa1d081c2565dc407f29d5794"),
    "ant-launcher.jar": ("org/apache/ant/ant-launcher/1.10.15/ant-launcher-1.10.15.jar", "5c8551990307a032336d98ddaed549a39a689f07d4d4c6b950601bf22b3d6a1b"),
    "json-test.jar": ("org/json/json/20240303/json-20240303.jar", "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"),
}
for name, (path, digest) in artifacts.items():
    destination = tools / name
    if not destination.exists():
        with urllib.request.urlopen("https://repo.maven.apache.org/maven2/" + path, timeout=60) as response:
            destination.write_bytes(response.read())
    if hashlib.sha256(destination.read_bytes()).hexdigest() != digest:
        raise RuntimeError("Checksum mismatch: " + name)

def run(command):
    subprocess.run([str(x) for x in command], cwd=root, check=True)

ant_classpath = os.pathsep.join(str(p) for p in [tools / "ant.jar", tools / "ant-launcher.jar", jdk / "lib/tools.jar"])
run([jdk / "bin/java", "-cp", ant_classpath, "org.apache.tools.ant.Main", "extensions"])
out = root / "test/out"
out.mkdir(parents=True, exist_ok=True)
sources = list((root / "src/ai/saauf/pollinations/helpers").glob("*.java")) + [root / "test/ApiTest.java"]
run([jdk / "bin/javac", "-source", "7", "-target", "7", "-proc:none", "-cp", tools / "json-test.jar", "-d", out] + sources)
run([jdk / "bin/java", "-cp", str(out) + os.pathsep + str(tools / "json-test.jar"), "ApiTest"])
run([os.sys.executable, root / "scripts/make-sample.py"])
with zipfile.ZipFile(root / "out/ai.saauf.pollinations.aix") as archive:
    descriptors = json.loads(archive.read("ai.saauf.pollinations/components.json"))
    assert descriptors[0]["type"] == "ai.saauf.pollinations.Pollinations"
    assert "ai.saauf.pollinations/classes.jar" in archive.namelist()
    assert "ai.saauf.pollinations/files/AndroidRuntime.jar" in archive.namelist()
with zipfile.ZipFile(root / "sample/PollinationsDemo.aia") as archive:
    names = archive.namelist()
    assert not any(Path(name).name in {"component.json", "component_build_info.json"} for name in names)
    source = next(name for name in names if name.endswith("Screen1.scm"))
    screen = json.loads(archive.read(source).decode().split("$JSON\n", 1)[1].rsplit("\n|#", 1)[0])
    components = {item["$Name"]: item["$Type"] for item in screen["Properties"]["$Components"]}
    blocks = E.fromstring(archive.read(source.replace(".scm", ".bky")))
    for mutation in blocks.iter("{https://developers.google.com/blockly/xml}mutation"):
        instance = mutation.get("instance_name")
        if instance:
            assert components[instance] == mutation.get("component_type"), instance
        if mutation.get("component_type") == descriptors[0]["name"]:
            method = mutation.get("method_name")
            event = mutation.get("event_name")
            if method:
                assert method in [item["name"] for item in descriptors[0]["methods"]], method
            if event:
                assert event in [item["name"] for item in descriptors[0]["events"]], event
    assert not any("sk_" in archive.read(name).decode(errors="ignore") for name in names if name.endswith((".scm", ".bky", ".properties")))
print("Extension packaging, sample structure and block references passed")
