package com.pulseguard.controlapi.repository.projection;

import com.pulseguard.controlapi.enums.MonitorStatus;

/** One row per status present in a project, with how many monitors hold it. */
public interface MonitorStatusCountProjection {

    MonitorStatus getStatus();

    long getCount();
}
