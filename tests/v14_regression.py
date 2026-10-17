from pathlib import Path
import itertools,json,re,random,hashlib
from playwright.sync_api import sync_playwright
ROOT=Path('/mnt/data/BaptistaBrain_v1_4/web')
html=(ROOT/'index.html').read_text().replace('<link rel="stylesheet" href="style.css">','<style>'+(ROOT/'style.css').read_text()+'</style>')
for file in ['teasers.js','codes.js','app.js']:
 html=html.replace(f'<script src="{file}"></script>', '<script>'+ (ROOT/file).read_text()+'</script>')
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox'])
 def getpage(size=(390,844),save=None):
  page=browser.new_page(viewport={'width':size[0],'height':size[1]})
  errors=[]
  page.on('pageerror',lambda e: errors.append(str(e)))
  page.evaluate('''(seed)=>{window.__S=seed||{};Object.defineProperty(window,'localStorage',{configurable:true,value:{getItem(k){return window.__S[k]||null},setItem(k,v){window.__S[k]=String(v)},removeItem(k){delete window.__S[k]}}})}''',save)
  page.set_content(html,wait_until='load')
  return page,errors
 for width,height in [(360,780),(390,844),(430,932),(900,1000)]:
  page,errors=getpage((width,height))
  assert page.locator('#home').is_visible()
  assert page.locator('.mode').count()==6
  assert page.evaluate('document.documentElement.scrollWidth')<=width
  page.locator('[data-mode="code"]').click()
  assert page.locator('.code-clue').count()==4
  assert page.locator('#codeInput').is_visible()
  if width==390:
   page.screenshot(path='/mnt/data/BaptistaBrain_v1_4/qa/codebreaker.png',full_page=True)
  assert not errors,errors
  page.close()
 print('PASS browser 4 viewports and code UI')
 # Test full 12 code puzzles are uniquely solvable, with exact clue language.
 page,errors=getpage()
 records=page.evaluate('window.BaptistaCodes.PUZZLES')
 possible=[''.join(p) for p in itertools.permutations('0123456789',3)]
 for record in records:
  remaining=[]
  for code in possible:
   if all((sum(a==b for a,b in zip(code,guess)),sum(c in code for c in guess)-sum(a==b for a,b in zip(code,guess)))==(right,wrong) for guess,right,wrong in record['clues']):remaining.append(code)
  assert remaining==[record['answer']], (record,remaining)
 print('PASS 12 mathematically unique code puzzles')
 page.close()
 # Simulate time elapsed in every mode; timer must not finish the round or question, but score remains positive.
 for mode in ['solo','relay','duel','challenge','code']:
  page,errs=getpage()
  page.clock.install()
  page.locator(f'[data-mode="{mode}"]').click()
  assert page.locator('#game').is_visible()
  before=page.locator('#questionNumber').inner_text()
  # Once the bonus window expires, all answers remain selectable, and no game-over fires.
  page.clock.fast_forward(500_000)
  assert page.locator('#game').is_visible(), f'Expired question ended game {mode}'
  assert page.locator('#questionNumber').inner_text()==before
  assert 'BONUS' in page.locator('#timeVal').inner_text()
  if mode=='code':
   answer=page.evaluate("window.BaptistaCodes.PUZZLES[0].answer")
   page.locator('#codeInput').fill(answer)
   page.locator('.code-entry button').click()
  elif page.locator('#choices button').count():
   page.locator('#choices button').first.click()
  elif page.locator('#memoryGrid button').count():
   page.locator('#revealSolution').click()
  assert page.locator('#nextQuestion').is_visible(), mode
  assert page.locator('#results').is_hidden(),mode
  assert not errs,(mode,errs)
  page.close()
 print('PASS timer expiry continues game across all bonus modes')
 # Test code input validation, incorrect guesses retained, turn handoffs, score event + normalizer compatibility.
 page,errs=getpage()
 page.locator('[data-mode="code"]').click()
 page.locator('#codeInput').fill('111');page.locator('.code-entry button').click()
 assert 'DIFFERENT' in page.locator('#feedback').inner_text()
 page.locator('#codeInput').fill('123');page.locator('.code-entry button').click()
 assert 'try again' in page.locator('#feedback').inner_text()
 assert page.locator('#game').is_visible()
 page.locator('#codeInput').fill('286');page.locator('.code-entry button').click()
 assert 'CORRECT' in page.locator('#feedback').inner_text()
 assert page.locator('#coachPanel').is_visible()
 old=page.evaluate('JSON.parse(window.__S["baptista_brain_family_v1"])')
 assert len(old['events'])==1 and next(iter(old['events'].values()))['mode']=='code'
 page.locator('#nextQuestion').click()
 assert page.locator('#dialog').is_visible()
 page.locator('.dialog-actions button.main').click()
 assert page.locator('#turnName').inner_text()=='May'
 assert page.locator('#questionNumber').inner_text()=='02 / 3'
 assert not errs,errs
 print('PASS invalid guesses, retries, worked feedback, save event, family turn handoff')
 page.close()
 # Retention: prior v1.2 player name and points survive without migration.
 saved={"schema":1,"players":[{"id":"saurabh","name":"Captain S","pace":"standard","edited":True},{"id":"may","name":"May","pace":"standard"},{"id":"mom","name":"Sharmila","pace":"relaxed"}],"active":"saurabh","events":{"old-score-v12":{"id":"old-score-v12","player":"saurabh","mode":"solo","correct":True,"points":55,"xp":25,"at":"2026-10-17T00:00:00.000Z","topic":"math","hintCount":0,"revealed":False,"day":""}},"matches":{},"legacy":{},"settings":{"sound":True}}
 page,errs=getpage(save={'baptista_brain_family_v1':json.dumps(saved)})
 assert page.locator('#helloName').inner_text()=='Captain S'
 assert '55' in page.locator('#players .player-card').first.inner_text()
 page.locator('[data-mode="code"]').click()
 page.locator('#codeInput').fill('286');page.locator('.code-entry button').click()
 after=page.evaluate('JSON.parse(window.__S["baptista_brain_family_v1"])')
 assert 'old-score-v12' in after['events'] and len(after['events'])==2
 print('PASS v1.2 score preservation and v1.4 save continuity')
 assert not errs,errs
 page.close()
 browser.close()
