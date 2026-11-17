import unittest
from unittest.mock import patch
import planner_pdf


def polygon(west, east, south=59.9, north=59.92):
    return {'type':'Polygon','coordinates':[[[west,south],[east,south],[east,north],[west,north],[west,south]]]}


def feature(geometry, **properties):
    return {'type':'Feature','geometry':geometry,'properties':properties}


class PDFAreaTests(unittest.TestCase):
    def assess(self, shot, zones):
        with patch('drone_zones.features_in_bbox',return_value=zones):
            return planner_pdf._shot_area_context(shot)

    def test_crossing_zone_between_waypoints(self):
        shot={'route':[[59.91,10.70],[59.91,10.72]],'simSmoothing':'direct'}
        zones={'features':[feature(polygon(10.709,10.711),name='Crossing zone',identifier='A')], 'source_date':'2026-11-10','stale':True,'origin':'community mirror'}
        result=self.assess(shot,zones)
        self.assertEqual(result['zones']['hits'][0]['identifier'],'A')
        self.assertTrue(result['zones']['stale'])

    def test_pdf_never_fetches_population(self):
        with patch('population_ssb.features_in_bbox',side_effect=AssertionError('SSB must not be fetched')) as population, patch('drone_zones.features_in_bbox',return_value={'features':[]}), patch.object(planner_pdf,'_basemap',return_value=None):
            data=planner_pdf.make_planner_pdf({'shots':[{'route':[[59.91,10.71]]}]})
        population.assert_not_called()
        self.assertTrue(data.startswith(b'%PDF-'))
        self.assertNotIn('population',planner_pdf._shot_area_context({'route':[]}))

    def test_polygon_hole_is_not_zone_overlap(self):
        geom=polygon(10.69,10.73)
        geom['coordinates'].append(polygon(10.70,10.72,59.905,59.915)['coordinates'][0])
        result=self.assess({'route':[[59.91,10.71]]},{'features':[feature(geom,name='Outside hole')]})
        self.assertEqual(result['zones']['hits'],[])

    def test_source_failure_does_not_prevent_export(self):
        with patch('drone_zones.features_in_bbox',side_effect=RuntimeError('offline')):
            result=planner_pdf._shot_area_context({'route':[[59.91,10.71]]})
        self.assertTrue(result['zones']['error'])
        with patch.object(planner_pdf,'_basemap',return_value=None),patch.object(planner_pdf,'_shot_area_context',return_value=result):
            data=planner_pdf.make_planner_pdf({'name':'Test','shots':[{'route':[[59.91,10.71]]}]})
        self.assertTrue(data.startswith(b'%PDF-'))

    def test_long_zone_list_stays_on_one_page_per_shot(self):
        context={'checked':'2026-11-17','zones':{'hits':[{'name':'Zone '+str(i),'message':'Long source message '*30} for i in range(40)],'date':'2026','origin':'official','stale':False,'truncated':False}}
        with patch.object(planner_pdf,'_basemap',return_value=None),patch.object(planner_pdf,'_shot_area_context',return_value=context):
            data=planner_pdf.make_planner_pdf({'shots':[{'route':[[59.91,10.71]]}]})
        import re
        self.assertEqual(len(re.findall(rb'/Type /Page\b',data)),1)
        with patch.object(planner_pdf,'_basemap',return_value=None),patch.object(planner_pdf,'_shot_area_context',return_value=context):
            data=planner_pdf.make_planner_pdf({'shots':[{'route':[[59.91,10.71]]}]*3})
        self.assertEqual(len(re.findall(rb'/Type /Page\b',data)),3)

if __name__=='__main__':unittest.main()
