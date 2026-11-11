"""Шукає контролери LEDnet поблизу по Bluetooth LE."""
import asyncio

from bleak import BleakScanner


async def main() -> None:
    found = await BleakScanner.discover(timeout=10.0, return_adv=True)
    for device, adv in found.values():
        name = device.name or adv.local_name or ""
        if "LEDnet" in name:
            print(f"{device.address}  {name}  RSSI={adv.rssi}")
            print(f"  services: {adv.service_uuids}")
            print(f"  manufacturer: { {k: v.hex() for k, v in adv.manufacturer_data.items()} }")


asyncio.run(main())
