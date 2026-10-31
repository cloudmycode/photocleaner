const fs=require('fs'),path=require('path');
const {chromium}=require('/Users/wang/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async()=>{
 const base=path.resolve(__dirname,'..');
 const project=path.resolve(base,'..');
 const files=[
 ['photo-icon',path.join(project,'output/google-play/icon/photo-cleaner-icon-hand-swipe-512.png'),256],
 ['security-icon','/Users/wang/Project/antivirus/Max2/max2/app/src/main/res/drawable-xxxhdpi/ic_launcher.png',192],
 ['photo-review',path.join(project,'output/google-play/en-US/raw/02-swipe.png'),640],
 ['photo-months',path.join(project,'output/google-play/en-US/raw/01-months.png'),640],
 ['photo-results',path.join(project,'output/google-play/en-US/raw/06-cleanup-complete.png'),640],
 ['coast',path.join(project,'android/app/src/androidTest/assets/store/coast.png'),800],
 ['lake',path.join(project,'android/app/src/androidTest/assets/store/lake.png'),700]
 ];
 const browser=await chromium.launch({headless:true,executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
 const page=await browser.newPage();
 for(const [name,file,width] of files){
  const src='data:image/png;base64,'+fs.readFileSync(file).toString('base64');
  const data=await page.evaluate(async({src,width})=>{
   const image=new Image();image.src=src;await image.decode();
   const c=document.createElement('canvas');c.width=width;c.height=Math.round(image.height/image.width*width);
   const ctx=c.getContext('2d');ctx.drawImage(image,0,0,c.width,c.height);
   return c.toDataURL('image/webp',.91).split(',')[1];
  },{src,width});
  fs.writeFileSync(path.join(base,'assets',name+'.webp'),Buffer.from(data,'base64'));
 }
 await browser.close();
})();
