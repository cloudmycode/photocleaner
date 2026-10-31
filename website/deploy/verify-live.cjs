const {chromium}=require('/Users/wang/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const fs=require('fs'),path=require('path');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
 try {
 const context=await browser.newContext();const page=await context.newPage();const errors=[];
 page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error')errors.push(m.text())});
 const origin='https://www.maxdevlab.com';const routes=['/','/apps/photo-cleaner.html','/apps/security-antivirus.html','/privacy/index.html','/privacy/photo-cleaner.html','/privacy/security-antivirus.html','/privacy/website.html','/terms/index.html','/terms/security-antivirus.html','/support/index.html'];
 const checks=[];
 for(const route of routes){const response=await page.goto(origin+route);await page.evaluate(()=>Promise.all([...document.images].map(i=>i.decode())));if(response.status()!==200)throw Error(route+' '+response.status());if(await page.locator('h1').count()!==1)throw Error(route+' h1');checks.push({route,status:response.status()});}
 for(const route of ['/app-ads.txt','/sitemap.xml','/robots.txt']){const response=await context.request.get(origin+route);if(response.status()!==200)throw Error(route);checks.push({route,status:response.status()});}
 const missing=await context.request.get(origin+'/missing-deployment-check');if(missing.status()!==404)throw Error('404 failed');
 await page.setViewportSize({width:1440,height:1000});await page.goto(origin+'/');await page.screenshot({path:path.join(__dirname,'live-desktop.png'),fullPage:true});
 await page.setViewportSize({width:390,height:844});await page.goto(origin+'/privacy/index.html');if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw Error('Mobile overflow');await page.screenshot({path:path.join(__dirname,'live-privacy-mobile.png'),fullPage:true});
 await page.getByRole('button',{name:'Open navigation'}).click();if(await page.locator('.menu-toggle').getAttribute('aria-expanded')!=='true')throw Error('Mobile menu');
 if(errors.length)throw Error(errors.join('\n'));
 fs.writeFileSync(path.join(__dirname,'live-verification.json'),JSON.stringify({origin,dns:'Normal browser resolution; TLS validation enabled',checks,missingPageStatus:missing.status(),mobileMenu:'passed',consoleErrors:errors},null,2));
 console.log('Live HTTPS: 10 pages, 3 public metadata files, 404, images and mobile navigation verified; no CSP or JavaScript errors.');
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
