from check_preset import PRESET, layout, render


if __name__ == "__main__":
    PRESET.write_bytes(render(layout()))
