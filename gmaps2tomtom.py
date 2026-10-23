#!/usr/bin/env python3
"""gmaps2tomtom: send Google Maps points and routes to an old TomTom (ONE, XL, GO...).

Paste (or copy, with clipboard watching on) a Google Maps link, short link,
coordinates or address. Then save as a route (itinerary, .itn) or as POIs (.ov2).
"""
from __future__ import annotations

import os
import queue
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import g2tt_core as core  # noqa: E402

APP = "gmaps2tomtom"
KIND_LABEL = {core.STOP: "Stop", core.VIA: "Via", core.SHAPE: "Shape"}


class App(tk.Tk):
    def __init__(self):
        super().__init__(className=APP)
        self.title("Google Maps → TomTom")
        icon = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon.png")
        if os.path.isfile(icon):
            try:
                self._icon = tk.PhotoImage(file=icon)
                self.iconphoto(True, self._icon)
            except tk.TclError:
                pass
        self.geometry("820x600")
        self.minsize(640, 480)
        self.points: list[core.Point] = []
        self.jobs: queue.Queue = queue.Queue()
        self.last_clip = ""
        self._build()
        self.detect_device(quiet=True)
        self.after(100, self._poll_jobs)
        self.after(1000, self._poll_clipboard)

    # ------------------------------------------------------------ layout
    def _build(self):
        pad = {"padx": 6, "pady": 3}

        dev = ttk.LabelFrame(self, text="TomTom")
        dev.pack(fill="x", **pad)
        self.dev_var = tk.StringVar()
        ttk.Entry(dev, textvariable=self.dev_var).pack(side="left", fill="x", expand=True, padx=4, pady=4)
        ttk.Button(dev, text="Detect", command=self.detect_device).pack(side="left", padx=2)
        ttk.Button(dev, text="Browse…", command=self.browse_device).pack(side="left", padx=2)
        self.dev_info = ttk.Label(dev, text="", width=28)
        self.dev_info.pack(side="left", padx=4)

        inp = ttk.LabelFrame(self, text="Paste Google Maps links, short links, coordinates or addresses (one per line)")
        inp.pack(fill="x", **pad)
        self.input = tk.Text(inp, height=4, wrap="char")
        self.input.pack(fill="x", padx=4, pady=4)
        row = ttk.Frame(inp)
        row.pack(fill="x", padx=4, pady=(0, 4))
        ttk.Button(row, text="Add", command=self.add_from_text).pack(side="left")
        ttk.Button(row, text="Paste & add", command=self.paste_and_add).pack(side="left", padx=4)
        self.watch_var = tk.BooleanVar(value=True)
        ttk.Checkbutton(row, text="Watch clipboard for Google Maps links", variable=self.watch_var).pack(side="left", padx=8)

        lst = ttk.LabelFrame(self, text="Points (double-click a name to rename)")
        lst.pack(fill="both", expand=True, **pad)
        cols = ("n", "kind", "name", "lat", "lon")
        self.tree = ttk.Treeview(lst, columns=cols, show="headings", selectmode="extended")
        for col, title, w, anchor in (("n", "#", 36, "e"), ("kind", "Type", 60, "w"), ("name", "Name", 360, "w"),
                                      ("lat", "Latitude", 100, "e"), ("lon", "Longitude", 100, "e")):
            self.tree.heading(col, text=title)
            self.tree.column(col, width=w, anchor=anchor, stretch=(col == "name"))
        sb = ttk.Scrollbar(lst, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=sb.set)
        self.tree.pack(side="left", fill="both", expand=True, padx=(4, 0), pady=4)
        sb.pack(side="left", fill="y", pady=4)
        self.tree.bind("<Double-1>", self.rename)
        self.tree.bind("<Delete>", lambda e: self.remove())
        side = ttk.Frame(lst)
        side.pack(side="left", fill="y", padx=4, pady=4)
        for text, cmd in (("Up", lambda: self.move(-1)), ("Down", lambda: self.move(1)),
                          ("Stop/Via", self.toggle_kind), ("Remove", self.remove), ("Clear", self.clear),
                          ("Open .itn…", self.open_itn)):
            ttk.Button(side, text=text, command=cmd, width=11).pack(pady=2)

        out = ttk.LabelFrame(self, text="Send to TomTom")
        out.pack(fill="x", **pad)
        r1 = ttk.Frame(out)
        r1.pack(fill="x", padx=4, pady=2)
        ttk.Label(r1, text="Name:").pack(side="left")
        self.name_var = tk.StringVar(value="Google route")
        ttk.Entry(r1, textvariable=self.name_var, width=30).pack(side="left", padx=4)
        self.depart_var = tk.BooleanVar(value=True)
        ttk.Checkbutton(r1, text="First point is the departure", variable=self.depart_var).pack(side="left", padx=8)
        r2 = ttk.Frame(out)
        r2.pack(fill="x", padx=4, pady=2)
        self.shape_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(r2, text="Follow the same roads: add extra points along the route (needs internet), total",
                        variable=self.shape_var).pack(side="left")
        self.shape_n = tk.IntVar(value=30)
        ttk.Spinbox(r2, from_=5, to=core.ITN_MAX, textvariable=self.shape_n, width=4).pack(side="left", padx=4)
        r3 = ttk.Frame(out)
        r3.pack(fill="x", padx=4, pady=(2, 6))
        ttk.Button(r3, text="Save route (Itinerary)", command=self.save_itn).pack(side="left")
        ttk.Button(r3, text="Save as POIs", command=self.save_ov2).pack(side="left", padx=4)
        ttk.Label(r3, text="in POI category:").pack(side="left", padx=(4, 0))
        self.cat_var = tk.StringVar(value="Google Maps")
        ttk.Entry(r3, textvariable=self.cat_var, width=18).pack(side="left", padx=4)
        ttk.Button(r3, text="Export .itn file…", command=self.export_itn).pack(side="left", padx=4)

        self.status = ttk.Label(self, text="Ready.", anchor="w", relief="sunken")
        self.status.pack(fill="x", side="bottom")

    # ------------------------------------------------------------ helpers
    def say(self, msg: str):
        self.status.config(text=msg)

    def refresh(self):
        sel = set(self.tree.selection())
        self.tree.delete(*self.tree.get_children())
        for i, p in enumerate(self.points):
            iid = str(i)
            self.tree.insert("", "end", iid=iid, values=(i + 1, KIND_LABEL[p.kind], p.name, f"{p.lat:.6f}", f"{p.lon:.6f}"))
            if iid in sel:
                self.tree.selection_add(iid)

    def selected(self) -> list[int]:
        return sorted(int(i) for i in self.tree.selection())

    def run_bg(self, func, done, busy_msg):
        """Run network work off the UI thread; `done(result)` runs back on the UI thread."""
        self.say(busy_msg)
        self.config(cursor="watch")

        def work():
            try:
                self.jobs.put((done, func(), None))
            except Exception as e:  # noqa: BLE001
                self.jobs.put((done, None, e))
        threading.Thread(target=work, daemon=True).start()

    def _poll_jobs(self):
        try:
            while True:
                done, result, err = self.jobs.get_nowait()
                self.config(cursor="")
                if err:
                    self.say(f"Error: {err}")
                    messagebox.showerror(APP, str(err))
                else:
                    done(result)
        except queue.Empty:
            pass
        self.after(100, self._poll_jobs)

    # ------------------------------------------------------------ device
    def detect_device(self, quiet=False):
        roots = core.find_tomtom_roots()
        if roots:
            self.dev_var.set(roots[0])
            info = core.read_bif(roots[0])
            name, hw = info.get("DeviceName", "TomTom"), info.get("DeviceVersionHW", "")
            self.dev_info.config(text=hw if hw.startswith(name.replace("TomTom ", "")) and hw else f"{name} {hw}".strip())
            self.say(f"Found TomTom at {roots[0]}")
        else:
            self.dev_info.config(text="not found")
            if not quiet:
                messagebox.showinfo(APP, "No TomTom found. Connect it by USB, wait until it shows as a drive, "
                                         "then press Detect or choose its folder with Browse.")

    def browse_device(self):
        d = filedialog.askdirectory(title="Choose the TomTom drive")
        if d:
            self.dev_var.set(d)
            info = core.read_bif(d)
            self.dev_info.config(text=info.get("DeviceName", "TomTom?" if not core.is_tomtom_root(d) else "TomTom"))

    def device_root(self) -> str | None:
        d = self.dev_var.get().strip()
        if d and os.path.isdir(d) and core.is_tomtom_root(d):
            return d
        self.detect_device(quiet=True)
        d = self.dev_var.get().strip()
        if d and os.path.isdir(d) and core.is_tomtom_root(d):
            return d
        messagebox.showwarning(APP, "TomTom not found. Connect it and press Detect, or use 'Export .itn file…'.")
        return None

    # ------------------------------------------------------------ input
    def add_from_text(self, text: str | None = None):
        from_box = text is None
        text = self.input.get("1.0", "end") if from_box else text
        if not text.strip():
            return

        def done(res: core.ParseResult):
            self.points += res.points
            self.refresh()
            if res.points and self.name_var.get() in ("", "Google route"):
                stops = [p for p in self.points if p.kind == core.STOP]
                if len(stops) >= 2:
                    self.name_var.set(f"{stops[0].name.split(',')[0]} - {stops[-1].name.split(',')[0]}"[:40])
                elif stops:
                    self.name_var.set(stops[0].name.split(",")[0][:40])
            if from_box:
                self.input.delete("1.0", "end")
            msg = f"Added {len(res.points)} point(s)."
            if res.warnings:
                msg += "  " + "  ".join(res.warnings)
            self.say(msg)
        self.run_bg(lambda: core.parse_input(text), done, "Reading link…")

    def paste_and_add(self):
        try:
            clip = self.clipboard_get()
        except tk.TclError:
            clip = ""
        self.last_clip = clip
        self.add_from_text(clip)

    def _poll_clipboard(self):
        if self.watch_var.get():
            try:
                clip = self.clipboard_get()
            except tk.TclError:
                clip = ""
            if clip and clip != self.last_clip:
                first = self.last_clip == ""
                self.last_clip = clip
                looks = ("google." in clip and "/maps" in clip) or "maps.app.goo.gl" in clip
                if looks and not first and len(clip) < 5000:
                    self.add_from_text(clip)
        self.after(1000, self._poll_clipboard)

    # ------------------------------------------------------------ list editing
    def move(self, delta):
        sel = self.selected()
        if not sel or any(not 0 <= i + delta < len(self.points) for i in sel):
            return
        for i in (sel if delta < 0 else reversed(sel)):
            self.points[i], self.points[i + delta] = self.points[i + delta], self.points[i]
        self.refresh()
        self.tree.selection_set([str(i + delta) for i in sel])

    def toggle_kind(self):
        for i in self.selected():
            p = self.points[i]
            p.kind = core.VIA if p.kind == core.STOP else core.STOP
        self.refresh()

    def remove(self):
        for i in reversed(self.selected()):
            del self.points[i]
        self.refresh()

    def clear(self):
        self.points.clear()
        self.refresh()
        self.name_var.set("Google route")

    def rename(self, event):
        iid = self.tree.identify_row(event.y)
        if not iid or self.tree.identify_column(event.x) != "#3":
            return
        x, y, w, h = self.tree.bbox(iid, "#3")
        var = tk.StringVar(value=self.points[int(iid)].name)
        e = ttk.Entry(self.tree, textvariable=var)
        e.place(x=x, y=y, width=w, height=h)
        e.focus_set()
        e.select_range(0, "end")

        def commit(_=None):
            self.points[int(iid)].name = var.get().strip() or self.points[int(iid)].name
            e.destroy()
            self.refresh()
        e.bind("<Return>", commit)
        e.bind("<FocusOut>", commit)
        e.bind("<Escape>", lambda _: e.destroy())

    def open_itn(self):
        path = filedialog.askopenfilename(filetypes=[("TomTom itinerary", "*.itn"), ("All files", "*")],
                                          initialdir=os.path.join(self.dev_var.get(), "itn"))
        if path:
            with open(path, "rb") as f:
                self.points += core.read_itn(f.read())
            self.name_var.set(os.path.splitext(os.path.basename(path))[0])
            self.refresh()

    # ------------------------------------------------------------ output
    def _route_points(self, then):
        if len(self.points) < 1:
            messagebox.showinfo(APP, "Add some points first.")
            return
        if self.shape_var.get() and len(self.points) >= 2:
            n = max(2, min(core.ITN_MAX, int(self.shape_n.get())))
            self.run_bg(lambda: core.add_shaping_points(self.points, n), then, "Getting the road shape…")
        else:
            then([p for p in self.points])

    def save_itn(self):
        root = self.device_root()
        if not root:
            return

        def write(pts):
            if len(pts) > core.ITN_MAX and not messagebox.askyesno(
                    APP, f"{len(pts)} points. Older TomToms may only accept {core.ITN_MAX}. Save anyway?"):
                return
            path = core.write_itn(root, self.name_var.get(), pts, self.depart_var.get())
            self.say(f"Saved {len(pts)} points to {path}")
            messagebox.showinfo(APP, f"Route saved as '{os.path.basename(path)}'.\n\nOn the TomTom: "
                                     "Main menu → Itinerary planning → Options → Load itinerary.\n\n"
                                     "Eject the TomTom safely before unplugging it.")
        self._route_points(write)

    def export_itn(self):
        def write(pts):
            path = filedialog.asksaveasfilename(defaultextension=".itn", initialfile=core.safe_filename(self.name_var.get()) + ".itn",
                                                filetypes=[("TomTom itinerary", "*.itn")])
            if path:
                with open(path, "wb") as f:
                    f.write(core.build_itn(pts, self.depart_var.get()))
                self.say(f"Exported {len(pts)} points to {path}")
        self._route_points(write)

    def save_ov2(self):
        root = self.device_root()
        if not root or not self.points:
            return
        mapdir = core.find_map_folder(root)
        if not mapdir:
            mapdir = filedialog.askdirectory(title="Choose the map folder on the TomTom", initialdir=root)
            if not mapdir:
                return
        pts = [p for p in self.points if p.kind == core.STOP] or self.points
        category = self.cat_var.get().strip() or "Google Maps"
        path, added = core.write_ov2(mapdir, category, pts)
        cat = os.path.splitext(os.path.basename(path))[0]
        skipped = len(pts) - added
        note = f" ({skipped} already there, skipped)" if skipped else ""
        self.say(f"Added {added} POI(s) to {path}{note}")
        messagebox.showinfo(APP, f"{added} POI(s) added to category '{cat}'{note}.\n\n"
                                 "On the TomTom: Navigate to → Point of Interest → choose that category.\n"
                                 "Tip: from there you can also add it to Favourites.")

def main():
    if "--selftest" in sys.argv:
        r = core.parse_input("38.7, -9.1", network=False)
        print("ok" if r.points else "fail")
        return
    App().mainloop()


if __name__ == "__main__":
    main()
