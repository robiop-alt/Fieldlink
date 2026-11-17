import unittest
from unittest.mock import patch
import planner_pdf


def polygon(west, east, south=59.9, north=59.92):
    return {'type':'Polygon','coordinates':[[[west,south],[east,south],[east,north],[west,north],[west,south]]]}


def feature(geometry, **properties):
    return {'type':'Feature','geometry':geometry,'properties':properties}


class PDFAreaTests(unittest.TestCase):
    def assess(self, shot, zones, population):
        with patch('drone_zones.features_in_bbox',return_value=zones), patch('population_ssb.features_in_bbox',return_value=population):
            return planner_pdf._shot_area_context(shot)

    def test_crossing_zone_between_waypoints_and_unknown_density(self):
        shot={'route':[[59.91,10.70],[59.91,10.72]],'simSmoothing':'direct'}
        zones={'features':[feature(polygon(10.709,10.711),name='Crossing zone',identifier='A')], 'source_date':'2026-11-10','stale':True,'origin':'community mirror'}
        population={'complete':True,'years':{'population':2025,'settlements':2026},'features':[
            feature(polygon(10.699,10.705),kind='population',density=0),
            feature(polygon(10.705,10.721),kind='population',density=None),
            feature(polygon(10.708,10.712),kind='settlement',name='Oslo')]}
        result=self.assess(shot,zones,population)
        self.assertEqual(result['zones']['hits'][0]['identifier'],'A')
        self.assertTrue(result['zones']['stale'])
        self.assertEqual(result['population']['density'],(0,0))
        self.assertEqual(result['population']['waypoints'],[(1,[0]),(2,[])])
        self.assertFalse(result['population']['covered'])
        self.assertEqual(result['population']['settlements'],['Oslo'])

    def test_polygon_hole_is_not_zone_overlap(self):
        geom=polygon(10.69,10.73)
        geom['coordinates'].append(polygon(10.70,10.72,59.905,59.915)['coordinates'][0])
        result=self.assess({'route':[[59.91,10.71]]},{'features':[feature(geom,name='Outside hole')]},{'complete':True,'features':[]})
        self.assertEqual(result['zones']['hits'],[])
        self.assertIsNone(result['population']['density'])

    def test_source_failure_does_not_prevent_export(self):
        with patch('drone_zones.features_in_bbox',side_effect=RuntimeError('offline')),patch('population_ssb.features_in_bbox',side_effect=ValueError('outside coverage')):
            result=planner_pdf._shot_area_context({'route':[[59.91,10.71]]})
        self.assertTrue(result['zones']['error'])
        self.assertTrue(result['population']['error'])
        with patch.object(planner_pdf,'_basemap',return_value=None),patch.object(planner_pdf,'_shot_area_context',return_value=result):
            data=planner_pdf.make_planner_pdf({'name':'Test','shots':[{'route':[[59.91,10.71]]}]})
        self.assertTrue(data.startswith(b'%PDF-'))

    def test_long_zone_list_stays_on_one_page_per_shot(self):
        context={'checked':'2026-11-17','zones':{'hits':[{'name':'Zone '+str(i),'message':'Long source message '*30} for i in range(40)],'date':'2026','origin':'official','stale':False,'truncated':False},'population':{'error':True}}
        with patch.object(planner_pdf,'_basemap',return_value=None),patch.object(planner_pdf,'_shot_area_context',return_value=context):
            data=planner_pdf.make_planner_pdf({'shots':[{'route':[[59.91,10.71]]}]})
        import re
        self.assertEqual(len(re.findall(rb'/Type /Page\b',data)),1)
        with patch.object(planner_pdf,'_basemap',return_value=None),patch.object(planner_pdf,'_shot_area_context',return_value=context):
            data=planner_pdf.make_planner_pdf({'shots':[{'route':[[59.91,10.71]]}]*3})
        self.assertEqual(len(re.findall(rb'/Type /Page\b',data)),3)

if __name__=='__main__':unittest.main()
