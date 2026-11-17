import math
import unittest
import xml.etree.ElementTree as ET
from population_ssb import parse_features, features_in_bbox, _mercator, _lonlat


def sample(value='5',next_page=False):
    x,y=_mercator(10,60);dx=500;dy=500
    coords=f'{x} {y} {x+dx} {y} {x+dx} {y+dy} {x} {y+dy} {x} {y}'
    more=' next="https://kart.ssb.no/next"' if next_page else ''
    return ET.fromstring(f'''<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0" xmlns:gml="http://www.opengis.net/gml/3.2" xmlns:ms="urn:ssb" numberMatched="unknown" numberReturned="1"{more}><wfs:member><ms:grid><ms:msGeometry><gml:Polygon srsName="urn:ogc:def:crs:EPSG::3857"><gml:exterior><gml:LinearRing><gml:posList srsDimension="2">{coords}</gml:posList></gml:LinearRing></gml:exterior></gml:Polygon></ms:msGeometry><ms:pop_tot>{value}</ms:pop_tot></ms:grid></wfs:member></wfs:FeatureCollection>''')

class PopulationTests(unittest.TestCase):
    def test_explicit_axes_clip_and_density(self):
        bounds=(60.0001,10.0001,60.001,10.001)
        features,complete=parse_features(sample(),'population',2025,bounds)
        self.assertTrue(complete)
        self.assertEqual(features[0]['properties']['density'],80)
        for ring in features[0]['geometry']['coordinates']:
            for lon,lat in ring:
                self.assertTrue(bounds[1]-1e-8<=lon<=bounds[3]+1e-8)
                self.assertTrue(bounds[0]-1e-8<=lat<=bounds[2]+1e-8)
    def test_missing_suppressed_and_zero_are_distinct(self):
        for value,expected in [('',None),('-1',None),('secret',None),('0',0)]:
            f,_=parse_features(sample(value),'population',2025,(59.99,9.99,60.1,10.1))
            self.assertEqual(f[0]['properties']['density'],expected)
    def test_partial_source_cannot_support_classification(self):
        _,complete=parse_features(sample(next_page=True),'population',2025,(59.99,9.99,60.1,10.1))
        self.assertFalse(complete)
    def test_unexpected_axis_order_is_rejected(self):
        root=sample();root.find('.//{http://www.opengis.net/gml/3.2}Polygon').set('srsName','urn:ogc:def:crs:EPSG::4326')
        with self.assertRaises(ValueError):parse_features(root,'population',2025,(59.99,9.99,60.1,10.1))
    def test_large_or_invalid_viewports_rejected_before_network(self):
        for bounds in [(59,10,61,11),(60,10,59,11),(math.nan,10,60,11),(78,10,79,11)]:
            with self.assertRaises(ValueError):features_in_bbox(bounds)
