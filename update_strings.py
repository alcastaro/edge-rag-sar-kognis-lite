import re

ES_FILE = 'app/src/main/res/values/strings_kognis.xml'
EN_FILE = 'app/src/main/res/values-en/strings_kognis.xml'

new_es_strings = """
    <!-- QUICK CHIPS -->
    <string name="chip_medevac">Pedir MEDEVAC 9-Line</string>
    <string name="chip_march">Protocolo MARCH</string>
    <string name="chip_bleeding">Detener Hemorragia</string>
    <string name="chip_salute">Reporte SALUTE</string>
    <string name="chip_support">Solicitar Apoyo</string>
    <string name="chip_break_contact">Romper Contacto</string>
    <string name="chip_lz">Marcar Zona LZ</string>
    <string name="chip_exfil">Ruta Exfil</string>
    <string name="chip_rally">Punto de Reunión</string>
"""

new_en_strings = """
    <!-- QUICK CHIPS -->
    <string name="chip_medevac">Request MEDEVAC 9-Line</string>
    <string name="chip_march">MARCH Protocol</string>
    <string name="chip_bleeding">Stop Bleeding</string>
    <string name="chip_salute">SALUTE Report</string>
    <string name="chip_support">Request Support</string>
    <string name="chip_break_contact">Break Contact</string>
    <string name="chip_lz">Mark LZ Zone</string>
    <string name="chip_exfil">Exfil Route</string>
    <string name="chip_rally">Rally Point</string>
"""

for fname, new_strings in [(ES_FILE, new_es_strings), (EN_FILE, new_en_strings)]:
    with open(fname, 'r') as f:
        content = f.read()
    
    # insert before </resources>
    if '</resources>' in content:
        content = content.replace('</resources>', new_strings + '</resources>')
        with open(fname, 'w') as f:
            f.write(content)

