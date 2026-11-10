"""Build deterministic, editable external UI template ZIPs. No network or third-party tools."""
from pathlib import Path
import argparse
import json
import zipfile

ROOT=Path(__file__).resolve().parents[1]/"pipeline-builder-ui/templates"

def package(check=False):
    import io
    for name in ("aurora","midnight"):
        source=ROOT/name
        manifest=json.loads((source/"manifest.json").read_text(encoding="utf-8"))
        output=io.BytesIO()
        with zipfile.ZipFile(output,"w",compression=zipfile.ZIP_STORED) as archive:
            for file in sorted(source.rglob("*")):
                if file.is_file():
                    info=zipfile.ZipInfo(file.relative_to(source).as_posix(),(2026,1,1,0,0,0))
                    info.create_system=3
                    info.external_attr=0o100644<<16
                    archive.writestr(info,file.read_bytes())
        target=ROOT/(name+".zip")
        if check:
            if not target.is_file() or target.read_bytes()!=output.getvalue():
                raise ValueError("Rebuild the template ZIP: "+name)
        else:target.write_bytes(output.getvalue())
        print(("Checked " if check else "Packaged ")+manifest["name"]+": "+str(target))

if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--check",action="store_true")
    package(parser.parse_args().check)
