"""Local-only revenue workbook, permission and real Chrome download checks.

Uses an existing administrator and a temporary default-VIEWER administrator;
does not create or modify business orders. Tokens are never saved to reports.
"""
import argparse
import io
import json
import re
import secrets
from pathlib import Path
import urllib.error
import urllib.request
from urllib.parse import urlencode
import xml.etree.ElementTree as ET
import zipfile
from ai_local_acceptance import Acceptance, ROOT
from playwright.sync_api import sync_playwright, expect


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8088')
    parser.add_argument('--output', type=Path, default=ROOT / '.work/export-acceptance')
    args = parser.parse_args()
    a = Acceptance(args.base_url, args.output)
    settings = dict(line.split('=',1) for line in (ROOT/'.env').read_text(encoding='utf-8-sig').splitlines()
                    if line and not line.startswith('#') and '=' in line)
    viewer_id = None
    viewer_name = 'ex_' + secrets.token_hex(5)
    password = secrets.token_hex(7)

    def request(path, token=None, status=200):
        req = urllib.request.Request(a.base+'/api'+path, headers={'Authorization': token or ''})
        try:
            with urllib.request.urlopen(req, timeout=45) as response:
                assert response.status == status
                return response.headers, response.read()
        except urllib.error.HTTPError as error:
            assert error.code == status, 'Unexpected status: '+str(error.code)
            return error.headers, error.read()

    try:
        a.token = a.http('/admin/login', {'name':settings.get('INITIAL_ADMIN_NAME','admin'),
            'password':settings['INITIAL_ADMIN_PASSWORD'], **a.captcha()})
        report = a.http('/statistic/revenue')
        params = urlencode({key:report[key] for key in ('startDate','endDate','granularity')})
        path = '/statistic/revenue/export?'+params
        headers, content = request(path, a.token)
        assert 'spreadsheetml.sheet' in headers.get('Content-Type','')
        assert headers.get('Cache-Control') == 'no-store'
        assert headers.get('Content-Disposition','').endswith('.xlsx"')
        ns = {'m':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
        with zipfile.ZipFile(io.BytesIO(content)) as archive:
            workbook = ET.fromstring(archive.read('xl/workbook.xml'))
            assert [sheet.attrib['name'] for sheet in workbook.find('m:sheets',ns)] == ['说明','汇总对比','每日明细','趋势汇总','影片排行','影厅排行']
            summary = ET.fromstring(archive.read('xl/worksheets/sheet2.xml'))
            cells = {cell.attrib['r']:cell for cell in summary.findall('.//m:c',ns)}
            for cell, metric in [('D2','grossRevenue'),('E2','refundAmount'),('F2','netRevenue')]:
                assert cells[cell].attrib.get('t') in (None,'n')
                assert abs(float(cells[cell].find('m:v',ns).text)-report['summary'][metric]) < 0.001
            daily = ET.fromstring(archive.read('xl/worksheets/sheet3.xml'))
            assert len(daily.findall('.//m:row',ns)) == len(report['daily'])+1
        (args.output/'revenue.xlsx').write_bytes(content)
        a.passed('real six-sheet workbook matches report amounts, refund accounting and daily row count')
        request(path, status=401)
        request('/statistic/revenue/export?startDate=2026-10-02&endDate=2026-10-01',a.token,status=400)
        a.passed('anonymous export and invalid dates fail before any workbook is sent')
        account = a.http('/admin/addAdmin', {'name':viewer_name,'password':password,'age':30})
        viewer_id = account['id']
        root_token = a.token
        a.token = None
        viewer_token = a.http('/admin/login',{'name':viewer_name,'password':password,**a.captcha()})
        a.token = root_token
        request(path,viewer_token,status=403)
        a.passed('fresh default viewer cannot export revenue')
        with sync_playwright() as p:
            browser = p.chromium.launch(channel='chrome',headless=True)
            try:
                def signed(token):
                    context = browser.new_context(locale='zh-CN',timezone_id='Asia/Shanghai',accept_downloads=True,viewport={'width':1440,'height':1000})
                    context.add_init_script('localStorage.setItem("cinema-auth",'+json.dumps(json.dumps({'token':token,'role':'ADMIN','profile':None}))+')')
                    page = context.new_page()
                    page.on('pageerror',lambda error:a.report['page_errors'].append(str(error)))
                    return page
                page = signed(root_token)
                page.goto(a.base+'/manage/revenue',wait_until='domcontentloaded')
                button = page.get_by_role('button',name='下载 Excel',exact=True)
                expect(button).to_be_enabled(timeout=45000)
                with page.expect_download() as pending:
                    button.click()
                download = pending.value
                assert download.suggested_filename.endswith('.xlsx')
                download.save_as(args.output/'chrome-revenue.xlsx')
                assert zipfile.is_zipfile(args.output/'chrome-revenue.xlsx')
                page.screenshot(path=str(args.output/'revenue-desktop.png'),full_page=True)
                page.set_viewport_size({'width':390,'height':844})
                expect(page.locator('.admin-aside')).to_be_hidden()
                expect(button).to_be_visible()
                page.screenshot(path=str(args.output/'revenue-mobile.png'),full_page=True)
                a.passed('actual Chrome downloads an xlsx from the revenue page')
                downloads=[]
                page.on('download',lambda result:downloads.append(result))
                page.route('**/api/statistic/revenue/export?*',lambda route:route.fulfill(status=200,content_type='application/json',body=json.dumps({'code':0,'msg':'导出验收模拟失败'})))
                button.click()
                expect(page.get_by_text('导出验收模拟失败',exact=True)).to_be_visible()
                assert not downloads
                page.unroute('**/api/statistic/revenue/export?*')
                a.passed('JSON business failure is displayed and never saved as xlsx')
                viewer = signed(viewer_token)
                viewer.goto(a.base+'/manage/revenue',wait_until='domcontentloaded')
                expect(viewer).to_have_url(re.compile(r'/403(?:\?|$)'))
                expect(viewer.get_by_role('button',name='下载 Excel',exact=True)).to_have_count(0)
                assert not a.report['page_errors']
                a.passed('viewer has no export action; Chrome reports no script errors')
                a.http('/admin/rbac/admins/'+str(viewer_id),{'roles':['FINANCE']},method='PUT')
                finance_headers, finance_content = request(path,viewer_token)
                assert 'spreadsheetml.sheet' in finance_headers.get('Content-Type','')
                assert zipfile.is_zipfile(io.BytesIO(finance_content))
                a.passed('finance role grants export to an already-issued token on its next request')
            finally:
                browser.close()
        a.report['completed']=True
    finally:
        a.report['cleanup_completed']=False
        try:
            if viewer_id:
                a.sql(f"DELETE FROM cinema_identity.admin_access_audit WHERE target_id={int(viewer_id)} OR actor_id={int(viewer_id)}; DELETE FROM cinema_identity.admin WHERE id={int(viewer_id)} AND name='{viewer_name}';")
            a.report['cleanup_completed']=True
        finally:
            (args.output/'report.json').write_text(json.dumps(a.report,ensure_ascii=False,indent=2),encoding='utf-8')


if __name__=='__main__':
    main()
