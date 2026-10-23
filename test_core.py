import os
import tempfile
import unittest

import g2tt_core as c

DIR_URL = ("https://www.google.com/maps/dir/Lisbon/Sintra/Cascais/@38.7601316,-9.4559645,11z/data=!4m19!4m18"
           "!1m5!1m1!1s0xd19331a61e4f33b:0x400ebbde49036d0!2m2!1d-9.1393366!2d38.7222524"
           "!1m5!1m1!1s0xd1ed09be5d23323:0x400ebbde4903720!2m2!1d-9.3229213!2d38.8233416"
           "!1m5!1m1!1s0xd1ec47379b79a97:0x870d3b7e1807bbc2!2m2!1d-9.4303646!2d38.6960614?entry=ttu")
DIR_VIA_URL = ("https://www.google.com/maps/dir/Lisbon/Sintra/Cascais/@38.7601316,-9.4559645,11z/data=!4m25!4m24"
               "!1m10!1m1!1s0xd19331a61e4f33b:0x400ebbde49036d0!2m2!1d-9.1393366!2d38.7222524"
               "!3m4!1m2!1d-9.2362374!2d38.6706256!3s0xd1ecba9a2b6d26f:0x5ad67c75ad299d60"
               "!1m5!1m1!1s0xd1ed09be5d23323:0x400ebbde4903720!2m2!1d-9.3229213!2d38.8233416"
               "!1m5!1m1!1s0xd1ec47379b79a97:0x870d3b7e1807bbc2!2m2!1d-9.4303646!2d38.6960614!3e0?entry=ttu")
PLACE_URL = ("https://www.google.com/maps/place/Torre+de+Bel%C3%A9m/@38.6916,-9.2185,17z/data=!3m1!4b1!4m6!3m5"
             "!1s0xd1ecb5d6c7e5a4f:0x2b2d2c2a!8m2!3d38.6915837!4d-9.2159767!16zL20vMDN4eDk?entry=ttu")


class Parse(unittest.TestCase):
    def test_dir(self):
        r = c.parse_input(DIR_URL, network=False)
        self.assertEqual([p.name for p in r.points], ["Lisbon", "Sintra", "Cascais"])
        self.assertAlmostEqual(r.points[1].lat, 38.8233416)
        self.assertAlmostEqual(r.points[1].lon, -9.3229213)
        self.assertTrue(all(p.kind == c.STOP for p in r.points))

    def test_dir_with_dragged_via(self):
        r = c.parse_input(DIR_VIA_URL, network=False)
        self.assertEqual([p.kind for p in r.points], [c.STOP, c.VIA, c.STOP, c.STOP])
        self.assertAlmostEqual(r.points[1].lat, 38.6706256)
        self.assertAlmostEqual(r.points[1].lon, -9.2362374)

    def test_place(self):
        r = c.parse_input(PLACE_URL, network=False)
        self.assertEqual(len(r.points), 1)
        self.assertEqual(r.points[0].name, "Torre de Belém")
        self.assertAlmostEqual(r.points[0].lat, 38.6915837)

    def test_coords_and_dms(self):
        r = c.parse_input("38.7, -9.1\n38°42'05.0\"N 9°08'21.6\"W", network=False)
        self.assertEqual(len(r.points), 2)
        self.assertAlmostEqual(r.points[1].lat, 38.70139, places=4)
        self.assertAlmostEqual(r.points[1].lon, -9.13933, places=4)

    def test_api_and_q(self):
        r = c.parse_input("https://www.google.com/maps/dir/?api=1&origin=38.7,-9.1&destination=38.8,-9.3"
                          "&waypoints=38.75,-9.2\nhttps://maps.google.com/maps?q=41.15,-8.61", network=False)
        self.assertEqual([(p.lat, p.lon) for p in r.points],
                         [(38.7, -9.1), (38.75, -9.2), (38.8, -9.3), (41.15, -8.61)])

    def test_place_coords_and_map_view(self):
        r = c.parse_input("https://www.google.com/maps/place/38.70,-9.21\n"
                          "https://www.google.com/maps/@40.1,-8.2,15z", network=False)
        self.assertEqual([(p.lat, p.lon) for p in r.points], [(38.70, -9.21), (40.1, -8.2)])

    def test_your_location_origin(self):
        url = ("https://www.google.com/maps/dir//Sintra/@38.8,-9.3,11z/data=!4m9!4m8!1m0"
               "!1m5!1m1!1s0xd1ed09be5d23323:0x400ebbde4903720!2m2!1d-9.3229213!2d38.8233416")
        r = c.parse_input(url, network=False)
        self.assertEqual([p.name for p in r.points], ["Sintra"])
        self.assertTrue(any("your location" in w for w in r.warnings))


class GooglePlaceLookup(unittest.TestCase):
    """Phone share links (q=<name, address>&ftid=<id>) carry no coordinates."""

    def test_reads_pin_from_place_details(self):
        page = '<a href="/maps/preview/place?q=x&amp;pb=%211m1">x</a>'
        details = ")]}'\n" + '[null,null,null,null,null,null,[null,null,null,null,null,null,null,null,null,' \
                  '[null,null,39.7305163,-8.9299386],null,"Restaurante Castros Reef & Beef"]]'
        calls = []

        def fake_get(url, timeout):
            calls.append(url)
            return page if len(calls) == 1 else details
        orig = c._google_get
        c._google_get = fake_get
        try:
            url = ("https://maps.google.com/maps?q=Restaurante+Castros+Reef+%26+Beef,+R.+dos+Carreirinhos+79,"
                   "+2430-177+Marinha+Grande,+Portugal&ftid=0xd220fc1e7dc5b47:0xc7335bacf4e93f77&entry=gps")
            r = c.parse_input(url)
        finally:
            c._google_get = orig
        self.assertEqual(len(r.points), 1)
        self.assertEqual(r.points[0].name, "Restaurante Castros Reef & Beef")
        self.assertAlmostEqual(r.points[0].lat, 39.7305163)
        self.assertAlmostEqual(r.points[0].lon, -8.9299386)
        self.assertIn("ftid=", calls[0])
        self.assertTrue(calls[1].startswith("https://www.google.com/maps/preview/place?q=x&pb="))


class Files(unittest.TestCase):
    def test_itn(self):
        pts = c.parse_input(DIR_VIA_URL, network=False).points
        data = c.build_itn(pts)
        lines = data.decode("cp1252").splitlines()
        self.assertEqual(lines[0], "-913934|3872225|Lisbon|4|")
        self.assertTrue(lines[1].endswith("|0|"))
        self.assertTrue(lines[-1].endswith("|Cascais|2|"))
        self.assertEqual(len(c.read_itn(data)), 4)

    def test_ov2_roundtrip_and_append(self):
        with tempfile.TemporaryDirectory() as d:
            pts = c.parse_input(PLACE_URL, network=False).points
            c.write_ov2(d, "Google Maps", pts)
            _, added = c.write_ov2(d, "Google Maps", pts + [c.Point("Ação", 38.5, -9.0)])
            self.assertEqual(added, 1)  # Torre de Belém already there
            with open(os.path.join(d, "Google Maps.ov2"), "rb") as f:
                back = c.read_ov2(f.read())
            self.assertEqual([p.name for p in back], ["Torre de Belém", "Ação"])
            self.assertAlmostEqual(back[0].lat, 38.69158, places=5)
            self.assertTrue(os.path.isfile(os.path.join(d, "Google Maps.bmp")))

    def test_write_itn_into_device_layout(self):
        with tempfile.TemporaryDirectory() as d:
            open(os.path.join(d, "ttgo.bif"), "w").close()
            path = c.write_itn(d, "My trip: day/1", [c.Point("A", 1, 2), c.Point("B", 3, 4)])
            self.assertEqual(path, os.path.join(d, "itn", "My trip_ day_1.itn"))


if __name__ == "__main__":
    unittest.main()
