const {chromium}=require('/Users/wang/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const fs=require('fs'),path=require('path');
(async()=>{
 const root=path.resolve(__dirname,'..');
 const browser=await chromium.launch({headless:true,executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
 const errors=[];const page=await browser.newPage({viewport:{width:1440,height:1000},deviceScaleFactor:1});
 page.on('pageerror',e=>errors.push(e.message));
 const routes=['index.html','apps/photo-cleaner.html','apps/security-antivirus.html','privacy/index.html','privacy/photo-cleaner.html','privacy/security-antivirus.html','privacy/website.html','terms/index.html','terms/security-antivirus.html','support/index.html','404.html'];
 const checks=[];
 for(const route of routes){
  const r=await page.goto('http://127.0.0.1:8787/'+route);await page.evaluate(()=>Promise.all([...document.images].map(im=>im.decode())));
  const info=await page.evaluate(()=>({title:document.title,h1:document.querySelectorAll('h1').length,overflow:document.documentElement.scrollWidth>innerWidth,brokenImages:[...document.images].filter(im=>!im.naturalWidth).length}));
  if(r.status()!==200||info.h1!==1||info.overflow||info.brokenImages)throw Error(route+JSON.stringify(info));
  checks.push({route,...info});
 }
 await page.goto('http://127.0.0.1:8787/');await page.screenshot({path:path.join(root,'preview/home-desktop.png'),fullPage:true});
 await page.screenshot({path:path.join(root,'preview/home-desktop-first-screen.png')});
 await page.goto('http://127.0.0.1:8787/privacy/photo-cleaner.html');await page.screenshot({path:path.join(root,'preview/privacy-desktop.png')});
 await page.goto('http://127.0.0.1:8787/support/index.html?app=security');
 if(await page.locator('#app').inputValue()!=='security')throw Error('App preselection failed');
 const invalid=await page.locator('#support-form').evaluate(f=>f.checkValidity());if(invalid)throw Error('Empty form accepted');
 await page.screenshot({path:path.join(root,'preview/support-desktop.png'),fullPage:true});
 const mobile=await browser.newPage({viewport:{width:390,height:844},deviceScaleFactor:1,isMobile:true,hasTouch:true});
 mobile.on('pageerror',e=>errors.push(e.message));
 for(const route of routes){
  await mobile.goto('http://127.0.0.1:8787/'+route);
  const overflow=await mobile.evaluate(()=>document.documentElement.scrollWidth>innerWidth);
  if(overflow)throw Error('Mobile overflow: '+route);
 }
 await mobile.goto('http://127.0.0.1:8787/');await mobile.evaluate(()=>Promise.all([...document.images].map(im=>im.decode())));
 await mobile.screenshot({path:path.join(root,'preview/home-mobile.png'),fullPage:true});
 await mobile.screenshot({path:path.join(root,'preview/home-mobile-first-screen.png')});
 await mobile.getByRole('button',{name:'Open navigation'}).click();
 if(await mobile.locator('.menu-toggle').getAttribute('aria-expanded')!=='true')throw Error('Mobile menu did not open');
 await mobile.locator('#main-nav').getByRole('link',{name:'Privacy',exact:true}).click();
 await mobile.waitForURL('**/privacy/index.html');
 await mobile.screenshot({path:path.join(root,'preview/privacy-mobile.png'),fullPage:true});
 await mobile.goto('http://127.0.0.1:8787/support/index.html');
 const question=mobile.getByText('How do I sort photos by month?',{exact:false});await question.click();
 if(await mobile.locator('.faq details').first().getAttribute('open')===null)throw Error('FAQ did not open');
 if(errors.length)throw Error(errors.join('\n'));
 fs.writeFileSync(path.join(root,'preview/verification.json'),JSON.stringify({pages:checks,mobileRoutesChecked:routes.length,mobileMenu:'passed',faq:'passed',supportPrefill:'passed',emptyFormRejected:true,consoleErrors:errors},null,2));
 await browser.close();console.log('11 desktop/mobile pages verified; preview screenshots saved.');
})();
