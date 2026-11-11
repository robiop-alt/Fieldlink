import unittest
from unittest.mock import patch

import drone_zones


class DroneZoneTests(unittest.TestCase):
    def test_current_download_name_match(self):
        page = '<a href="/Downloads/NOR_ED269_compliant_geozones_2026-11-10.json">ED-269</a>'
        self.assertEqual(
            drone_zones.DOWNLOAD_RE.findall(page),
            ['/Downloads/NOR_ED269_compliant_geozones_2026-11-10.json']
        )

    def test_bbox_filter_and_safe_properties(self):
        zones = [{
            'name': 'Airport A', 'identifier': 'test',
            'restriction': 'REQ_AUTHORISATION',
            'reason': ['AIR_TRAFFIC'],
            'message': '<script>alert(1)</script>',
            'geometry': [{
                'lowerLimit': 0, 'upperLimit': 300,
                'upperVerticalReference': 'AGL',
                'horizontalProjection': {
                    'type': 'Polygon',
                    'coordinates': [[[10.0, 59.0], [11.0, 59.0],
                                     [11.0, 60.0], [10.0, 59.0]]]
                }
            }]
        }]
        with patch.object(drone_zones, 'get_zones', return_value={
            'zones': zones, 'source_date': '2026-11-10', 'stale': False
        }):
            inside = drone_zones.features_in_bbox((59.1, 10.1, 59.9, 10.9))
            outside = drone_zones.features_in_bbox((62.0, 12.0, 63.0, 13.0))
        self.assertEqual(len(inside['features']), 1)
        self.assertEqual(inside['features'][0]['geometry']['type'], 'Polygon')
        self.assertEqual(len(outside['features']), 0)
        self.assertEqual(inside['source_date'], '2026-11-10')

    def test_cache_discovery_and_source_date(self):
        import json
        page = b'<a href="/Downloads/NOR_ED269_compliant_geozones_2026-11-10.json">Data</a>'
        zones = [{
            'name': 'Test', 'geometry': [
                {'horizontalProjection': {'type': 'Polygon',
                                          'coordinates': [[[11, 60], [12, 60], [11, 60]]]}}
            ]
        }]
        with patch.object(drone_zones, '_download', side_effect=[page, json.dumps(zones).encode()]):
            drone_zones._CACHE['loaded'] = 0
            result = drone_zones.get_zones()
        self.assertEqual(result['source_date'], '2026-11-10')
        self.assertIn('/Downloads/NOR_ED269_compliant_geozones_2026-11-10.json', result['url'])


if __name__ == '__main__':
    unittest.main()
