# Third-party notices

The Mr. Calibrator app (module `mrcal`) uses the following third-party material.

## AutoEq (MIT License)

The equalization-curve shaping (smoothing with a wide treble window, slope limiting with peak/dip protection,
treble gain factor, sharpness limit for peaking filters, fixed-band graphic-EQ fitting) follows the approach of
[AutoEq](https://github.com/jaakkopasanen/AutoEq). The headphone target curves (Harman Over-Ear 2018 and 2015,
Harman In-Ear 2019 and its less-bass variant, Harman Over-Ear 2018 less-bass variant, ISO 11904-1 and B&K 5128
diffuse field, oratory1990 In-Ear and Optimum Hi-Fi, AutoEq In-Ear, InnerFidelity 2017) were sampled from the
target files in that repository.

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

## HouseCurve target tables

The room and car target tables (B&K, the Toole-inspired Harman in-room tilt, Car A, Car B, Car C) are numeric curve
tables published by [HouseCurve](https://housecurve.com) (Greg Wilding), based on B&K publication 17-197,
Dr. Floyd Toole's work, the JBL curve and a Crutchfield curve. Only the numbers are used. HouseCurve does not state
a licence for the tables.

## Vazirmatn font (SIL Open Font License 1.1)

The Persian UI uses [Vazirmatn](https://github.com/rastikerdar/vazirmatn) under the SIL OFL 1.1. The font files and
the full licence text are downloaded from the project's release tag `v33.003` at build time (see
`mrcal/build.gradle.kts`) and bundled with the app.

## Standards and band names

The X-curve (ISO 2969 / SMPTE) is a published standard; only numeric values describing its shape are used. The
sound-group ranges use the common audio-engineering band names, and the speech band is the ITU-T G.712 telephone
band (300-3400 Hz). Product and company names belong to their owners.

## Samsung

Not affiliated with or endorsed by Samsung. The app talks to the system app "Sound quality and effects"
(`com.sec.android.app.soundalive`) through the content provider that app exports.
