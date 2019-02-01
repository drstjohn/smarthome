/**
 * Copyright (c) 2014,2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.smarthome.binding.hue.internal;

import java.util.List;
import java.util.Map;
import java.lang.reflect.Type;
import com.google.gson.reflect.TypeToken;


/**
 * Detailed group information.
 *
 * @author Q42, standalone Jue library (https://github.com/Q42/Jue)
 * @author Denis Dudnik - moved Jue library source code inside the smarthome Hue binding
 */
public class FullGroup extends Group {
    public static final String CONFIG_TRANSITIONTIME = "transitiontime";


    public static final Type GSON_TYPE = new TypeToken<Map<String, FullGroup>>() {
    }.getType();
    private State action;
    private List<String> lights;
    private Integer transitiontime;

    FullGroup() {
        //this.transitiontime = 400;
    }

    FullGroup(State initialAction) {
        action = initialAction;
    }

    /**
     * Returns the last sent state update to the group.
     * This does not have to reflect the current state of the group.
     *
     * @return last state update
     */
    public State getAction() {
        return action;
    }

    /**
     * Returns a list of the lights in the group.
     *
     * @return lights in the group
     */
    public List<HueObject> getLights() {
        return Util.idsToLights(lights);
    }

    /**
     * Returns the state transitin time of the light group.
     *
     * @return state transition time
     */

    public Integer getTransitionTime() {
        return transitiontime;
    }

    /**
     * Sets the state transition time of the light group.
     */
    public void setTransitionTime(Integer transitiontime) {
        this.transitiontime = transitiontime;

    }
}
