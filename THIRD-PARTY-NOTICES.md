# Third-party notices

TalQ for Android is licensed under the Apache License 2.0 (see `LICENSE`). The app as built
(`generic` flavor, release runtime classpath) includes the components below. The list is
grouped by license and was taken from each component's own published metadata (its Maven
POM); a release build may leave out parts that R8 found unused.

## Apache License 2.0

The full text is in `LICENSE`.

- **AndroidX / Jetpack** (The Android Open Source Project): Activity, Annotation, AppCompat,
  Arch Core, Autofill, Browser, Collection, Compose (Runtime, UI, Foundation, Animation,
  Material, Material 3, Material Icons), Concurrent, Core, CustomView, DataStore, Emoji2,
  ExifInterface, Graphics, Interpolator, Lifecycle, Media3 (ExoPlayer, OkHttp data source,
  UI), Navigation, NavigationEvent, ProfileInstaller, RecyclerView, Room, SavedState, SQLite,
  Startup, Tracing, VectorDrawable, VersionedParcelable, Window, WorkManager
- **Kotlin** standard library, **kotlinx.coroutines**, **kotlinx.serialization**,
  **kotlinx-io**, **kotlinx-datetime** and other JetBrains Kotlin libraries (JetBrains s.r.o.
  and contributors), including the JetBrains Compose Multiplatform and AndroidX Lifecycle /
  SavedState ports
- **Ktor** client (JetBrains s.r.o. and contributors)
- **OkHttp** and **Okio** (Square, Inc.)
- **Coil** (Coil Contributors)
- **UnifiedPush Android connector** (UnifiedPush contributors)
- **Tink** (Google LLC)
- **Guava**, **Error Prone annotations**, **JSpecify**, **FindBugs JSR-305 annotations**,
  **Accompanist**, **Gson** (Google LLC and contributors)
- **kaml** (Charles Korn), **snakeyaml-engine-kmp** (Piotr Krzemiński and contributors),
  **urlencoder** (Erik C. Thauvin)

## BSD 3-Clause License

- **Protocol Buffers** (`com.google.protobuf:protobuf-java`, and the copy AndroidX DataStore
  bundles as `datastore-preferences-external-protobuf`)

```
Copyright 2008 Google Inc.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of the copyright holder nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## MIT License

- **SLF4J API** (`org.slf4j:slf4j-api`, used by Ktor)

```
Copyright (c) 2004-2023 QOS.ch
All rights reserved.

Permission is hereby granted, free  of charge, to any person obtaining
a  copy  of this  software  and  associated  documentation files  (the
"Software"), to  deal in  the Software without  restriction, including
without limitation  the rights to  use, copy, modify,  merge, publish,
distribute,  sublicense, and/or sell  copies of  the Software,  and to
permit persons to whom the Software  is furnished to do so, subject to
the following conditions:

The  above  copyright  notice  and  this permission  notice  shall  be
included in all copies or substantial portions of the Software.

THE  SOFTWARE IS  PROVIDED  "AS  IS", WITHOUT  WARRANTY  OF ANY  KIND,
EXPRESS OR  IMPLIED, INCLUDING  BUT NOT LIMITED  TO THE  WARRANTIES OF
MERCHANTABILITY,    FITNESS    FOR    A   PARTICULAR    PURPOSE    AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE,  ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```
