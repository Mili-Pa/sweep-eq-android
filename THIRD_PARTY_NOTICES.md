# Third-party notices

The Mr. Calibrator app (module `mrcal`) uses the following third-party material.

## AutoEq (MIT License)

The equalization-curve shaping (smoothing with a wide treble window, slope limiting with peak/dip protection,
treble gain factor, sharpness limit for peaking filters, fixed-band graphic-EQ fitting) follows the approach of
[AutoEq](https://github.com/jaakkopasanen/AutoEq). Target values for Harman Over-Ear 2018, Harman Over-Ear 2015 and
ISO 11904-1 diffuse field were sampled from the target files in that repository.

```
MIT License

Copyright (c) 2018-2022 Jaakko Pasanen

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Vazirmatn font (SIL Open Font License 1.1)

The Persian UI uses [Vazirmatn](https://github.com/rastikerdar/vazirmatn) under the SIL OFL 1.1. The font files and
the full licence text are downloaded from the project's release tag `v33.003` at build time (see
`mrcal/build.gradle.kts`) and bundled with the app.

## Reference curves and standards

B&K house curve, X-curve (ISO 2969 / SMPTE) and Harman target curves are published research and standards; only
numeric values describing their shapes are used. Product and company names belong to their owners.

## Samsung

Not affiliated with or endorsed by Samsung. The app talks to the system app "Sound quality and effects"
(`com.sec.android.app.soundalive`) through the content provider that app exports.
