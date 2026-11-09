# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
from pathlib import Path
p=Path('sqlite/CMakeLists.txt')
s=p.read_text()
a=s.index('    check_c_source_runs(')
b=s.index('    if(HAVE_GNU_STRERROR_R)', a)
s=s[:a]+r'''    include(CheckCSourceCompiles)
    check_c_source_compiles("
    #include <string.h>
    _Static_assert(__builtin_types_compatible_p(__typeof__(strerror_r(0, (char*)0, 0)), char*), \"GNU strerror_r returns char pointer\");
    int main(void) { return 0; }
    " HAVE_GNU_STRERROR_R)
'''+s[b:]
p.write_text(s)
