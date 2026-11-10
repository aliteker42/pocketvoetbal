package com.opencapture.openpocketcine.physical

import com.opencapture.monitorui.MonitorCapabilities
import com.opencapture.opencine.appuitesting.ConformanceApp
import com.opencapture.opencine.appuitesting.ConformanceProfile
import com.opencapture.openpocketcine.MainActivity

/** PHYSICAL, opt-in: the shared record cell against a real saved camera (see the OpenCine base). */
class PhysicalRecordProbe : com.opencapture.opencine.appuitesting.PhysicalRecordProbe(PocketPhysical)

private object PocketPhysical : ConformanceApp {
    override val activity = MainActivity::class.java
    // Capabilities name evidence only here; the probe reads the real camera, not a review profile.
    override val profile = ConformanceProfile("PocketCine", MonitorCapabilities())
}
