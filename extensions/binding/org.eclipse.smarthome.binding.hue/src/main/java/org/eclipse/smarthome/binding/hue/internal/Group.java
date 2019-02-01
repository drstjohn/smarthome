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

import java.lang.reflect.Type;
import java.util.Map;

import com.google.gson.reflect.TypeToken;

import com.google.gson.annotations.SerializedName;

/**
 * Basic group information.
 *
 * @author Q42, standalone Jue library (https://github.com/Q42/Jue)
 * @author Denis Dudnik - moved Jue library source code inside the smarthome Hue binding
 */
public class Group {
    public static final Type GSON_TYPE = new TypeToken<Map<String, Group>>() {
    }.getType();

    private String type;
    @SerializedName("class")
    private String roomClass;
    private String id;
    private String name;

    Group() {
        this.type = "LightGroup";
        this.roomClass = "";
        this.id = "0";
        this.name = "Group 0";
    }

    void setType(String type) {
        this.type = type;
    }

    void setRoomClass(String roomClass) {
        this.roomClass = roomClass;
    }

    void setName(String name) {
        this.name = name;
    }

    void setId(String id) {
        this.id = id;
    }

    /**
     * Returns if the group can be modified.
     * Currently only returns false for the all lights pseudo group.
     *
     * @return modifiability of group
     */
    public boolean isModifiable() {
        return !id.equals("0");
    }

    /**
     * Returns the type of group
     *
     * @return type
     */
    public String getType() {
        return this.type;
    }

    /**
     * Returns the room class. 
     * Only relevant to groups of type "Room"
     *
     * @return roomClass 
     */
    public String getRoomClass() {
        return this.roomClass;
    }

    /**
     * Returns the id of the group.
     *
     * @return id
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the name of the group.
     *
     * @return name
     */
    public String getName() {
        return name;
    }
}
