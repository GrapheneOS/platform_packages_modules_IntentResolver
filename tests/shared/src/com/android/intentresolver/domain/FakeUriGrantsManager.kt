/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.intentresolver.domain

import android.app.IUriGrantsManager
import android.content.pm.ParceledListSlice
import android.net.Uri
import android.os.IBinder

class FakeUriGrantsManager(val inaccessibleUris: MutableSet<Uri> = mutableSetOf()) :
    IUriGrantsManager {

    override fun checkGrantUriPermission_ignoreNonSystem(
        sourceUid: Int,
        targetPkg: String?,
        uri: Uri?,
        modeFlags: Int,
        userId: Int,
    ): Int {
        if (inaccessibleUris.contains(uri)) throw SecurityException()
        return 0
    }

    override fun takePersistableUriPermission(
        uri: Uri?,
        modeFlags: Int,
        toPackage: String?,
        userId: Int,
    ) {
        throw NotImplementedError()
    }

    override fun releasePersistableUriPermission(
        uri: Uri?,
        modeFlags: Int,
        toPackage: String?,
        userId: Int,
    ) {
        throw NotImplementedError()
    }

    override fun grantUriPermissionFromOwner(
        owner: IBinder?,
        fromUid: Int,
        targetPkg: String?,
        uri: Uri?,
        mode: Int,
        sourceUserId: Int,
        targetUserId: Int,
    ) {
        throw NotImplementedError()
    }

    override fun getGrantedUriPermissions(
        packageName: String?,
        userId: Int,
    ): ParceledListSlice<*>? {
        throw NotImplementedError()
    }

    override fun clearGrantedUriPermissions(packageName: String?, userId: Int) {
        throw NotImplementedError()
    }

    override fun getUriPermissions(
        packageName: String?,
        incoming: Boolean,
        persistedOnly: Boolean,
    ): ParceledListSlice<*>? {
        throw NotImplementedError()
    }

    override fun asBinder(): IBinder? {
        throw NotImplementedError()
    }
}
