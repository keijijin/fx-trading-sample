#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root/backend"
# Docker/Podman must be available. Do not silently skip the integration suite.
# On a controlled rootless Podman host only, export TESTCONTAINERS_RYUK_DISABLED=true
# if Ryuk cannot mount its socket. The suite stops its own database and broker.
mvn -B clean verify "$@"
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
expected = {'TradeFlowIntegrationTest': 9, 'JsonContractTest': 3}
for name, count in expected.items():
    reports = list(Path('integration-tests/target/surefire-reports').glob(f'TEST-*.{name}.xml'))
    assert len(reports) == 1, f'Missing or ambiguous report: {name}'
    root = ET.parse(reports[0]).getroot()
    result = {k: int(root.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')}
    print(name, result)
    assert result['tests'] >= count and not any(result[k] for k in ('failures', 'errors', 'skipped')), result
PY
