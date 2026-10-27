import subprocess, random, os, colorsys
from concurrent.futures import ThreadPoolExecutor
random.seed(7)
genres=["Rock","Pop","Jazz","Electronic","Hip-Hop","Folk","Classical","Metal","Ambient","Soul"]
words="Night Light River Glass Echo Ember Static Velvet Paper Signal Harbor Neon Silver Ghost Summer Winter Hollow Golden Atlas Drift Mirror Cinder Orbit Tidal Wild Quiet Broken Northern Electric Lunar".split()
artists=[f"{random.choice(words)} {random.choice(words)}s" for _ in range(160)]
jobs=[]
for a_i,artist in enumerate(artists):
    for al in range(2):
        album=f"{random.choice(words)} {random.choice(words)}"
        h=random.random()
        r,g,b=[int(255*x) for x in colorsys.hsv_to_rgb(h,0.6,0.8)]
        cov=f"cov/{a_i}_{al}.jpg"
        jobs.append(("cov",cov,f"0x{r:02x}{g:02x}{b:02x}"))
        for t in range(4 if al==0 else 3):
            title=f"{random.choice(words)} {random.choice(words)}"
            out=f"Perf/{a_i:03d}-{al}-{t}.mp3"
            jobs.append(("trk",out,cov,title,artist,album,str(t+1),random.choice(genres),str(random.randint(1970,2024))))
covs=[j for j in jobs if j[0]=="cov"]; trks=[j for j in jobs if j[0]=="trk"]
def mkcov(j): subprocess.run(["ffmpeg","-loglevel","error","-y","-f","lavfi","-i",f"color=c={j[2]}:s=96x96","-frames:v","1",j[1]],check=True)
def mktrk(j):
    _,out,cov,title,artist,album,tn,genre,year=j
    subprocess.run(["ffmpeg","-loglevel","error","-y","-i","base.mp3","-i",cov,"-map","0","-map","1","-c","copy","-id3v2_version","3",
      "-metadata",f"title={title}","-metadata",f"artist={artist}","-metadata",f"album={album}","-metadata",f"track={tn}",
      "-metadata",f"genre={genre}","-metadata",f"date={year}","-metadata:s:v","title=Album cover","-metadata:s:v","comment=Cover (front)",out],check=True)
with ThreadPoolExecutor(4) as ex: list(ex.map(mkcov,covs))
with ThreadPoolExecutor(4) as ex: list(ex.map(mktrk,trks))
print(len(trks))
