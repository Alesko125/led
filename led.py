"""Керування RGBW-стрічкою через контролер LEDnetWF (Magic Home) по Bluetooth LE.

Приклади:
    python led.py status
    python led.py on
    python led.py off
    python led.py rgb 255 80 0        # колір, білий канал вимикається
    python led.py white 128           # лише білий канал 0..255
    python led.py rgbw 255 0 0 100    # колір і білий разом
"""
import asyncio
import json
import sys

from bleak import BleakClient, BleakScanner

ADDRESS = "B4:E8:42:E9:B6:1D"
WRITE = "0000ff01-0000-1000-8000-00805f9b34fb"
NOTIFY = "0000ff02-0000-1000-8000-00805f9b34fb"


def with_checksum(data: list[int]) -> bytes:
    return bytes(data + [sum(data) & 0xFF])


def wrap(payload: bytes, seq: int, expects_reply: bool) -> bytes:
    """Обгортка LEDnetWF: 00 seq 80 00 00 len len+1 type payload."""
    cmd_type = 0x0A if expects_reply else 0x0B
    return bytes([0x00, seq & 0xFF, 0x80, 0x00, 0x00, len(payload), len(payload) + 1, cmd_type]) + payload


def parse_state(raw: bytes) -> dict:
    return {
        "увімкнена": raw[2] == 0x23,
        "rgb": (raw[6], raw[7], raw[8]),
        "white": raw[9],
        "модель": hex(raw[1]),
        "режим": f"{raw[3]:02x} {raw[4]:02x}",  # 61/ff — статичний колір; інше — ефекти/миготіння
    }


def build_command(args: list[str]) -> bytes | None:
    cmd, nums = args[0], [int(x) for x in args[1:]]
    if any(not 0 <= n <= 255 for n in nums):
        raise SystemExit("значення мають бути в межах 0..255")
    if cmd == "status":
        return None
    if cmd == "on":
        return with_checksum([0x71, 0x23, 0x0F])
    if cmd == "off":
        return with_checksum([0x71, 0x24, 0x0F])
    if cmd == "rgb" and len(nums) == 3:
        return with_checksum([0x31, *nums, 0x00, 0xF0, 0x0F])
    if cmd == "white" and len(nums) == 1:
        return with_checksum([0x31, 0, 0, 0, nums[0], 0x0F, 0x0F])
    if cmd == "rgbw" and len(nums) == 4:
        return with_checksum([0x31, *nums, 0x00, 0x0F])
    raise SystemExit(__doc__)


async def main(args: list[str]) -> None:
    command = build_command(args)
    device = await BleakScanner.find_device_by_address(ADDRESS, timeout=10.0)
    if device is None:
        raise SystemExit(f"Контролер {ADDRESS} не знайдено поруч")

    reply: asyncio.Future[bytes] = asyncio.get_running_loop().create_future()

    def on_notify(_, data: bytearray) -> None:
        start = data.find(b"{")
        if start < 0 or reply.done():
            return
        payload = json.loads(data[start:].decode())["payload"]
        reply.set_result(bytes.fromhex(payload))

    # Windows при швидкому перепідключенні іноді віддає неповний список каналів — тоді пробуємо ще раз.
    for attempt in range(3):
        async with BleakClient(device) as client:
            if client.services.get_characteristic(NOTIFY) is None:
                await asyncio.sleep(1)
                continue
            await client.start_notify(NOTIFY, on_notify)
            if command is not None:
                await client.write_gatt_char(WRITE, wrap(command, seq=1, expects_reply=False), response=False)
                await asyncio.sleep(1.5)  # контролер оновлює стан не одразу (плавний перехід)
            await client.write_gatt_char(
                WRITE, wrap(with_checksum([0x81, 0x8A, 0x8B]), seq=2, expects_reply=True), response=False
            )
            state = await asyncio.wait_for(reply, timeout=5)
            print(parse_state(state))
            return
    raise SystemExit("Не вдалося отримати канали контролера, спробуйте ще раз")


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1:] or ["status"]))
