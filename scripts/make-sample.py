from pathlib import Path
import json
import zipfile
import xml.etree.ElementTree as E

root = Path(__file__).resolve().parents[1]
extension = root / "out/ai.saauf.pollinations.aix"
namespace = "Pollinations"
types = {}
components = []

def component(name, kind, version, **properties):
    types[name] = kind
    item = {"$Name": name, "$Type": kind, "$Version": str(version), "Uuid": str(100 + len(components))}
    item.update(properties)
    components.append(item)

component("Heading", "Label", 5, Text="Pollinations Blocks", FontSize="24", FontBold="True")
component("Status", "Label", 5, Text="Sign in with your own Pollen. No key is included in this project.")
component("SignInButton", "Button", 7, Text="Sign in")
component("SignOutButton", "Button", 7, Text="Sign out")
component("Prompt", "TextBox", 6, Hint="Describe what you want", Text="Hello from Pollinations", Width="-2", MultiLine="True")
component("Model", "TextBox", 6, Hint="Model ID; empty uses the server default", Width="-2")
component("Voice", "TextBox", 6, Hint="Voice; empty uses the server default", Width="-2")
for name, label in [("AskButton", "Ask"), ("ImageButton", "Generate image"), ("SpeakButton", "Speak"), ("RecordButton", "Record audio"), ("StopButton", "Stop recording"), ("ModelsButton", "Show text models"), ("CancelButton", "Discard pending results")]:
    component(name, "Button", 7, Text=label)
component("ImagePicker", "ImagePicker", 5, Text="Pick image and ask")
component("Preview", "Image", 6, Width="-2", Height="200", ScalePictureToFit="True")
component("Output", "Label", 5, Text="Results appear here", Width="-2")
component("Player", "Player", 6)
component("Pollinations1", namespace, 1)

xml = E.Element("xml", {"xmlns": "https://developers.google.com/blockly/xml"})
counter = 0

def block(kind):
    global counter
    counter += 1
    return E.Element("block", {"type": kind, "id": "pollinations-" + str(counter)})

def field(parent, name, text):
    E.SubElement(parent, "field", {"name": name}).text = text

def value(parent, name, child):
    E.SubElement(parent, "value", {"name": name}).append(child)

def text(message):
    b = block("text")
    field(b, "TEXT", message)
    return b

def variable(name):
    b = block("lexical_variable_get")
    mutation = E.SubElement(b, "mutation")
    E.SubElement(mutation, "eventparam", {"name": name})
    field(b, "VAR", name)
    return b

def property(name, prop, new_value=None):
    b = block("component_set_get")
    E.SubElement(b, "mutation", {"component_type": types[name], "set_or_get": "get" if new_value is None else "set", "property_name": prop, "is_generic": "false", "instance_name": name})
    field(b, "COMPONENT_SELECTOR", name)
    field(b, "PROP", prop)
    if new_value is not None:
        value(b, "VALUE", new_value)
    return b

def method(name, function, *arguments):
    b = block("component_method")
    E.SubElement(b, "mutation", {"component_type": types[name], "method_name": function, "is_generic": "false", "instance_name": name})
    field(b, "COMPONENT_SELECTOR", name)
    for i, argument in enumerate(arguments):
        value(b, "ARG" + str(i), argument)
    return b

def event(name, function, action):
    b = block("component_event")
    b.set("x", str((len(xml) % 3) * 500 + 20))
    b.set("y", str((len(xml) // 3) * 240 + 20))
    E.SubElement(b, "mutation", {"component_type": types[name], "is_generic": "false", "instance_name": name, "event_name": function})
    field(b, "COMPONENT_SELECTOR", name)
    E.SubElement(b, "statement", {"name": "DO"}).append(action)
    xml.append(b)

event("SignInButton", "Click", method("Pollinations1", "SignIn"))
logout = method("Pollinations1", "SignOut")
E.SubElement(logout, "next").append(property("Status", "Text", text("Signed out")))
event("SignOutButton", "Click", logout)
event("AskButton", "Click", method("Pollinations1", "AskText", property("Prompt", "Text"), property("Model", "Text")))
event("ImageButton", "Click", method("Pollinations1", "GenerateImage", property("Prompt", "Text"), property("Model", "Text")))
event("SpeakButton", "Click", method("Pollinations1", "Speak", property("Prompt", "Text"), property("Model", "Text"), property("Voice", "Text")))
event("RecordButton", "Click", method("Pollinations1", "RecordAudio"))
event("StopButton", "Click", method("Pollinations1", "StopRecording"))
event("ModelsButton", "Click", method("Pollinations1", "LoadModels", text("text")))
event("CancelButton", "Click", method("Pollinations1", "Cancel"))
event("ImagePicker", "AfterPicking", method("Pollinations1", "AskAboutImage", property("Prompt", "Text"), property("ImagePicker", "Selection"), property("Model", "Text")))
event("Pollinations1", "RecordingReady", method("Pollinations1", "Transcribe", variable("path"), property("Model", "Text")))
event("Pollinations1", "Result", property("Output", "Text", variable("text")))
event("Pollinations1", "Error", property("Status", "Text", variable("message")))
event("Pollinations1", "SignInCode", property("Status", "Text", variable("code")))
event("Pollinations1", "SignInComplete", property("Status", "Text", text("Signed in. Choose a model from the live list.")))
choose = block("controls_if")
E.SubElement(choose, "mutation", {"else": "1"})
equal = block("logic_compare")
field(equal, "OP", "EQ")
value(equal, "A", variable("operation"))
value(equal, "B", text("image"))
value(choose, "IF0", equal)
E.SubElement(choose, "statement", {"name": "DO0"}).append(property("Preview", "Picture", variable("path")))
play = property("Player", "Source", variable("path"))
E.SubElement(play, "next").append(method("Player", "Start"))
E.SubElement(choose, "statement", {"name": "ELSE"}).append(play)
event("Pollinations1", "FileReady", choose)
E.SubElement(xml, "yacodeblocks", {"ya-version": "224", "language-version": "36"})

scm = {"YaVersion": "224", "Source": "Form", "Properties": {"$Name": "Screen1", "$Type": "Form", "$Version": "30", "Uuid": "0", "Title": "Pollinations Blocks", "Scrollable": "True", "$Components": components}}
project = "main=appinventor.ai_Saauf.PollinationsDemo.Screen1\nname=PollinationsDemo\nassets=../assets\nsource=../src\nbuild=../build\nversioncode=1\nversionname=1.0\nuseslocation=False\nsizing=Responsive\nshowlistsasjson=True\nactionbar=True\n"
source = "src/appinventor/ai_Saauf/PollinationsDemo/"
destination = root / "sample/PollinationsDemo.aia"
with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
    archive.writestr("youngandroidproject/project.properties", project)
    archive.writestr(source + "Screen1.scm", "#|\n$JSON\n" + json.dumps(scm) + "\n|#")
    archive.writestr(source + "Screen1.bky", E.tostring(xml, encoding="unicode"))
    with zipfile.ZipFile(extension) as aix:
        for item in aix.infolist():
            if not item.is_dir() and Path(item.filename).name not in {"component.json", "component_build_info.json"}:
                archive.writestr("assets/external_comps/" + item.filename, aix.read(item.filename))
print(destination)
