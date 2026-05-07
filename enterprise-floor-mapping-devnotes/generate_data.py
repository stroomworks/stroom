import random
from datetime import datetime, timedelta

# Data pools for variety
event_types = ["gate-event", "printer-event", "network-device-event", "security-alert", "badge-scan"]
statuses = ["OK", "FAIL", "WARN", "PENDING", "UNAUTHORIZED"]
users = [
    "joe.blogs@example.org", "jane.doe@stroomworks.io", "tech.support@example.net",
    "admin.one@corp.local", "guest.user.742@outside.com", "dev.lead@example.com",
    "facility.mgr@stroom.gov", "audit.team@example.org"
]
gate_locations = ["G-NORTH-01", "G-SOUTH-22", "G-EAST-04", "G-MAIN-ENTRANCE", "G-SEC-RECEPTION"]
printer_locations = ["P-L3-RM4", "P-L1-CAFETERIA", "P-L2-CORRIDOR", "P-EXEC-OFFICE"]
network_locations = ["C-office-1", "C-office-2", "C-office-3", "C-office-4"]

# Success/Failure message templates
success_messages = {
    "gate-event": ["{user} has successfully entered through {loc}", "{user} has safely exited building"],
    "printer-event": ["Print job completed successfully at {loc}", "Printer {loc} online and ready"],
    "network-device-event": ["Network connection established via {loc}", "Switch {loc} port active"],
    "security-alert": ["Area {loc} verified secure", "Security check at {loc} passed"],
    "badge-scan": ["Badge read successful at {loc}", "Access granted for {user} at {loc}"]
}

failure_messages = {
    "gate-event": ["{user} access denied at {loc}", "{user} tailgating alert at {loc}", "Gate {loc} malfunction"],
    "printer-event": ["Print job failed at {loc}: Paper jam", "Printer {loc} offline", "Out of toner at {loc}"],
    "network-device-event": ["Connection timeout at {loc}", "Switch {loc} port down", "Packet loss detected at {loc}"],
    "security-alert": ["Intrusion detected near {loc}!", "Tamper alarm triggered at {loc}", "Unauthorized access attempt"],
    "badge-scan": ["Invalid badge scanned at {loc}", "Insufficient clearance for {user} at {loc}", "Expired credentials"]
}

def generate_timestamp():
    base_time = datetime(2026, 4, 1)
    random_seconds = random.randint(0, 30 * 24 * 3600)
    dt = base_time + timedelta(seconds=random_seconds)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.%fZ")

def generate_record():
    etype = random.choice(event_types)
    user = random.choice(users)
    timestamp = generate_timestamp()
    
    # 20% chance to omit status (defaults to OK)
    include_status = random.random() > 0.2
    status = random.choices(statuses, weights=[80, 5, 5, 5, 5])[0] if include_status else "OK"
    
    # Determine location based on type
    if etype == "gate-event":
        loc = random.choice(gate_locations)
    elif etype == "printer-event":
        loc = random.choice(printer_locations)
    else:
        loc = random.choice(network_locations)

    record = f'    <record>\n'
    record += f'        <data name="type" value="{etype}"/>\n'
    record += f'        <data name="timestamp" value="{timestamp}"/>\n'
    record += f'        <data name="thing-idref" value="{user}"/>\n'
    record += f'        <data name="location-idref" value="{loc}"/>\n'
    
    if include_status:
        record += f'        <data name="status" value="{status}"/>\n'
    
    # Message logic: Success if OK, Failure otherwise
    user_name = user.split('@')[0].replace('.', ' ').title()
    if status == "OK":
        msg = random.choice(success_messages[etype]).format(user=user_name, loc=loc)
    else:
        msg = random.choice(failure_messages[etype]).format(user=user_name, loc=loc)
        
    record += f'        <data name="message" value="{msg}"/>\n'
    record += f'    </record>'
    return record

def main():
    print(f"Generating 2000 records with optional status and logic-based messages...")
    with open("generated-data.xml", "w") as f:
        f.write("<records>\n")
        for _ in range(2000):
            f.write(generate_record() + "\n")
        f.write("</records>\n")
    print("Done.")

if __name__ == "__main__":
    main()
