package my.diplom.aritmia

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyConfigurationInstrumentedTest {

    @Test
    fun localMedicalDataIsNotBackedUpAndLegacyStoragePermissionsAreAbsent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageManager = context.packageManager
        val applicationInfo = packageManager.getApplicationInfo(context.packageName, 0)

        assertFalse(
            "Android backup must stay disabled for local medical history and session data",
            applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0
        )

        val packageInfo = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val requestedPermissions = packageInfo.requestedPermissions?.toSet().orEmpty()

        assertFalse(
            "Legacy external-storage read permission must not be requested",
            Manifest.permission.READ_EXTERNAL_STORAGE in requestedPermissions
        )
        assertFalse(
            "Legacy external-storage write permission must not be requested",
            Manifest.permission.WRITE_EXTERNAL_STORAGE in requestedPermissions
        )
    }
}
