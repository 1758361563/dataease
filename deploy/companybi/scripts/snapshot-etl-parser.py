"""Generate comparison hashes using the original Python statements() function only."""
import ast
import hashlib
import sys
from pathlib import Path

original = Path(sys.argv[1])
fixtures = Path(sys.argv[2])
module = ast.parse(original.read_text(encoding="utf-8"))
function = next(n for n in module.body if isinstance(n, ast.FunctionDef) and n.name == "statements")
namespace = {}
exec(compile(ast.Module(body=[function], type_ignores=[]), "<original statements>", "exec"), namespace)
for path in fixtures.glob("refresh-*.sql"):
    parts = namespace["statements"](path.read_text(encoding="utf-8"))
    path.with_suffix(".sha256").write_text(
        "\n".join(hashlib.sha256(s.encode()).hexdigest() for s in parts) + "\n", encoding="utf-8"
    )
    print(path.name, len(parts), "statements")
