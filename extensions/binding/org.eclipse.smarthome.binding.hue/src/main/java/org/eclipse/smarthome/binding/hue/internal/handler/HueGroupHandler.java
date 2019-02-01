/**
 * Copyright (c) 2014,2018 Contributors to the Eclipse Foundation
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
package org.eclipse.smarthome.binding.hue.internal.handler;

import static org.eclipse.smarthome.binding.hue.internal.HueBindingConstants.*;
import static org.eclipse.smarthome.binding.hue.internal.FullGroup.*;

import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.math.BigDecimal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.binding.hue.internal.FullHueObject;
import org.eclipse.smarthome.binding.hue.internal.FullGroup;
import org.eclipse.smarthome.binding.hue.internal.HueBridge;
import org.eclipse.smarthome.binding.hue.internal.State;
import org.eclipse.smarthome.binding.hue.internal.State.ColorMode;
import org.eclipse.smarthome.binding.hue.internal.StateUpdate;
import org.eclipse.smarthome.core.library.types.HSBType;
import org.eclipse.smarthome.core.library.types.IncreaseDecreaseType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link HueGroupHandler} is the handler for a hue bridge light group. It uses the {@link HueClient} to execute the actual
 * command.
 *
 * @author Dennis Nobel - Initial contribution
 * @author Oliver Libutzki
 * @author Kai Kreuzer - stabilized code
 * @author Andre Fuechsel - implemented switch off when brightness == 0, changed to support generic thing types, changed
 *         the initialization of properties
 * @author Thomas Höfer - added thing properties
 * @author Jochen Hiller - fixed status updates for reachable=true/false
 * @author Markus Mazurczak - added code for command handling of OSRAM PAR16 50
 *         bulbs
 * @author Yordan Zhelev - added alert and effect functions
 * @author Denis Dudnik - switched to internally integrated source of Jue library
 * @author Christoph Weitkamp - Added support for bulbs using CIE XY colormode only
 */
@NonNullByDefault
public class HueGroupHandler extends BaseThingHandler implements GroupStatusListener {

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Stream.of(THING_TYPE_ON_OFF_GROUP,
        THING_TYPE_COLOR_GROUP, THING_TYPE_COLOR_TEMPERATURE_GROUP, THING_TYPE_EXTENDED_COLOR_GROUP, 
        THING_TYPE_DIMMABLE_GROUP).collect(Collectors.toSet());

    public static final String NORMALIZE_ID_REGEX = "[^a-zA-Z0-9_]";

    @NonNullByDefault({})
    private String groupId;

    private @Nullable Integer lastSentColorTemp;
    private @Nullable Integer lastSentBrightness;
    private @Nullable Integer transitiontime;

    private final Logger logger = LoggerFactory.getLogger(HueGroupHandler.class);

    private boolean propertiesInitializedSuccessfully = false;

    private @Nullable HueClient hueClient;

    @Nullable
    ScheduledFuture<?> scheduledFuture;

    public HueGroupHandler(Thing hueGroup) {
        super(hueGroup);
    }

    @Override
    public void initialize() {
        logger.debug("Initializing hue group handler.");
        Bridge bridge = getBridge();
        initializeThing((bridge == null) ? null : bridge.getStatus());
    }

    private void initializeThing(@Nullable ThingStatus bridgeStatus) {
        logger.debug("initializeThing thing {} bridge status {}", getThing().getUID(), bridgeStatus);
        final String configGroupId = (String) getConfig().get(GROUP_ID);
        if (configGroupId != null) {
            groupId = configGroupId;
            // note: this call implicitly registers our handler as a listener on
            // the bridge
            if (getHueClient() != null) {
                if (bridgeStatus == ThingStatus.ONLINE) {
                    updateStatus(ThingStatus.ONLINE);
                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
                }
            } else {
                updateStatus(ThingStatus.OFFLINE);
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error-no-group-id");
        }
    }

    @Override
    public void dispose() {
        logger.debug("Hue group handler disposed. Unregistering listener.");
        if (groupId != null) {
            HueClient bridgeHandler = getHueClient();
            if (bridgeHandler != null) {
                bridgeHandler.unregisterGroupStatusListener(this);
                hueClient = null;
            }
            groupId = null;
        }
    }

    private @Nullable FullGroup getGroup() {
        HueClient bridgeHandler = getHueClient();
        if (bridgeHandler != null) {
            return bridgeHandler.getGroupById(groupId);
        }
        return null;
    }

    @Override
    public void handleConfigurationUpdate(Map<String, Object> configurationParameters) {
        if (configurationParameters.containsKey(CONFIG_TRANSITIONTIME)) {
//            FullGroup group = getGroup();
//            if (group == null) {
//                logger.debug("hue group not known on bridge. Cannot handle command.");
//                return;
//            }
//            group.setTransitionTime(new Integer(((BigDecimal)configurationParameters.get(CONFIG_TRANSITIONTIME)).intValue()));
            this.transitiontime = new Integer(((BigDecimal)configurationParameters.get(CONFIG_TRANSITIONTIME)).intValue());
        }

        super.handleConfigurationUpdate(configurationParameters);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        HueClient hueBridge = getHueClient();
        if (hueBridge == null) {
            logger.warn("hue bridge handler not found. Cannot handle group command without bridge.");
            return;
        }

        FullGroup group = getGroup();
        if (group == null) {
            logger.debug("hue group not known on bridge. Cannot handle command.");
            return;
        }

        StateUpdate groupState = null;
        switch (channelUID.getId()) {
            case CHANNEL_COLORTEMPERATURE:
                if (command instanceof PercentType) {
                    groupState = LightStateConverter.toColorTemperatureLightState((PercentType) command);
                } else if (command instanceof OnOffType) {
                    groupState = LightStateConverter.toOnOffLightState((OnOffType) command);
                } else if (command instanceof IncreaseDecreaseType) {
                    groupState = convertColorTempChangeToStateUpdate((IncreaseDecreaseType) command, group);
                }
                break;
            case CHANNEL_BRIGHTNESS:
                if (command instanceof PercentType) {
                    groupState = LightStateConverter.toBrightnessLightState((PercentType) command);
                } else if (command instanceof OnOffType) {
                    groupState = LightStateConverter.toOnOffLightState((OnOffType) command);
                } else if (command instanceof IncreaseDecreaseType) {
                    groupState = convertBrightnessChangeToStateUpdate((IncreaseDecreaseType) command, group);
                }
                if (groupState != null && lastSentColorTemp != null) {
                    // make sure that the group also has the latest color temp
                    // this might not have been yet set in the group, if it was off
                    groupState.setColorTemperature(lastSentColorTemp);
                }
                break;
            case CHANNEL_SWITCH:
                logger.trace("CHANNEL_SWITCH handling command {}", command);
                if (command instanceof OnOffType) {
                    groupState = LightStateConverter.toOnOffLightState((OnOffType) command);
                }
                if (groupState != null && lastSentColorTemp != null) {
                    // make sure that the group also has the latest color temp
                    // this might not have been yet set in the group, if it was off
                    groupState.setColorTemperature(lastSentColorTemp);
                }
                break;
            case CHANNEL_COLOR:
                if (command instanceof HSBType) {
                    HSBType hsbCommand = (HSBType) command;
                    if (hsbCommand.getBrightness().intValue() == 0) {
                        groupState = LightStateConverter.toOnOffLightState(OnOffType.OFF);
                    } else {
                        groupState = LightStateConverter.toColorLightState(hsbCommand, group.getAction());
                    }
                } else if (command instanceof PercentType) {
                    groupState = LightStateConverter.toBrightnessLightState((PercentType) command);
                } else if (command instanceof OnOffType) {
                    groupState = LightStateConverter.toOnOffLightState((OnOffType) command);
                } else if (command instanceof IncreaseDecreaseType) {
                    groupState = convertBrightnessChangeToStateUpdate((IncreaseDecreaseType) command, group);
                }
                break;
            case CHANNEL_ALERT:
                if (command instanceof StringType) {
                    groupState = LightStateConverter.toAlertState((StringType) command);
                    if (groupState == null) {
                        // Unsupported StringType is passed. Log a warning
                        // message and return.
                        logger.warn("Unsupported String command: {}. Supported commands are: {}, {}, {} ", command,
                                LightStateConverter.ALERT_MODE_NONE, LightStateConverter.ALERT_MODE_SELECT,
                                LightStateConverter.ALERT_MODE_LONG_SELECT);
                        return;
                    } else {
                        scheduleAlertStateRestore(command);
                    }
                }
                break;
            case CHANNEL_EFFECT:
                if (command instanceof OnOffType) {
                    groupState = LightStateConverter.toOnOffEffectState((OnOffType) command);
                }
                break;
        }
        if (groupState != null) {
            if (this.transitiontime != null) {
                groupState.setTransitionTime(this.transitiontime);
            }
            // Cache values which we have sent
            Integer tmpBrightness = groupState.getBrightness();
            if (tmpBrightness != null) {
                lastSentBrightness = tmpBrightness;
            }
            Integer tmpColorTemp = groupState.getColorTemperature();
            if (tmpColorTemp != null) {
                lastSentColorTemp = tmpColorTemp;
            }
            hueBridge.updateGroupState(group, groupState);
        } else {
            logger.warn("Command sent to an unknown channel id: {}", channelUID);
        }
    }


    private @Nullable StateUpdate convertColorTempChangeToStateUpdate(IncreaseDecreaseType command, FullGroup group) {
        StateUpdate stateUpdate = null;
        Integer currentColorTemp = getCurrentColorTemp(group.getAction());
        if (currentColorTemp != null) {
            int newColorTemp = LightStateConverter.toAdjustedColorTemp(command, currentColorTemp);
            stateUpdate = new StateUpdate().setColorTemperature(newColorTemp);
        }
        return stateUpdate;
    }

    private @Nullable Integer getCurrentColorTemp(@Nullable State groupState) {
        Integer colorTemp = lastSentColorTemp;
        if (colorTemp == null && groupState != null) {
            colorTemp = groupState.getColorTemperature();
        }
        return colorTemp;
    }

    private @Nullable StateUpdate convertBrightnessChangeToStateUpdate(IncreaseDecreaseType command, FullGroup group) {
        StateUpdate stateUpdate = null;
        Integer currentBrightness = getCurrentBrightness(group.getAction());
        if (currentBrightness != null) {
            int newBrightness = LightStateConverter.toAdjustedBrightness(command, currentBrightness);
            stateUpdate = createBrightnessStateUpdate(currentBrightness, newBrightness);
        }
        return stateUpdate;
    }

    private @Nullable Integer getCurrentBrightness(@Nullable State groupState) {
        Integer brightness = lastSentBrightness;
        if (brightness == null && groupState != null) {
            if (!groupState.isOn()) {
                brightness = 0;
            } else {
                brightness = groupState.getBrightness();
            }
        }
        return brightness;
    }

    private StateUpdate createBrightnessStateUpdate(int currentBrightness, int newBrightness) {
        StateUpdate groupUpdate = new StateUpdate();
        if (newBrightness == 0) {
            groupUpdate.turnOff();
        } else {
            groupUpdate.setBrightness(newBrightness);
            if (currentBrightness == 0) {
                groupUpdate.turnOn();
            }
        }
        return groupUpdate;
    }

    protected synchronized @Nullable HueClient getHueClient() {
        if (hueClient == null) {
            Bridge bridge = getBridge();
            if (bridge == null) {
                return null;
            }
            ThingHandler handler = bridge.getHandler();
            if (handler instanceof HueClient) {
                hueClient = (HueClient) handler;
                hueClient.registerGroupStatusListener(this);
            } else {
                return null;
            }
        }
        return hueClient;
    }

    @Override
    public void onGroupStateChanged(@Nullable HueBridge bridge, FullGroup fullGroup) {
        logger.trace("onGroupStateChanged() was called");

        if (!fullGroup.getId().equals(groupId)) {
            logger.trace("Received state change for another handler's group ({}). Will be ignored.", fullGroup.getId());
            return;
        }

        lastSentColorTemp = null;
        lastSentBrightness = null;

        // update status (ONLINE)
        updateStatus(ThingStatus.ONLINE);

        HSBType hsbType = LightStateConverter.toHSBType(fullGroup.getAction());
        if (!fullGroup.getAction().isOn()) {
            hsbType = new HSBType(hsbType.getHue(), hsbType.getSaturation(), new PercentType(0));
        }
        updateState(CHANNEL_COLOR, hsbType);

        ColorMode colorMode = fullGroup.getAction().getColorMode();
        if (ColorMode.CT.equals(colorMode)) {
            PercentType colorTempPercentType = LightStateConverter.toColorTemperaturePercentType(fullGroup.getAction());
            updateState(CHANNEL_COLORTEMPERATURE, colorTempPercentType);
        } else {
            updateState(CHANNEL_COLORTEMPERATURE, UnDefType.NULL);
        }

        PercentType brightnessPercentType = LightStateConverter.toBrightnessPercentType(fullGroup.getAction());
        if (!fullGroup.getAction().isOn()) {
            brightnessPercentType = new PercentType(0);
        }
        updateState(CHANNEL_BRIGHTNESS, brightnessPercentType);

        if (fullGroup.getAction().isOn()) {
            updateState(CHANNEL_SWITCH, OnOffType.ON);
        } else {
            updateState(CHANNEL_SWITCH, OnOffType.OFF);
        }

        StringType stringType = LightStateConverter.toAlertStringType(fullGroup.getAction());
        if (!stringType.toString().equals("NULL")) {
            updateState(CHANNEL_ALERT, stringType);
            scheduleAlertStateRestore(stringType);
        }
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        HueClient handler = getHueClient();
        if (handler != null) {
            FullGroup group = handler.getGroupById(groupId);
            if (group != null) {
                onGroupStateChanged(null, group);
            }
        }
    }

    @Override
    public void onGroupRemoved(@Nullable HueBridge bridge, FullGroup group) {
        if (group.getId().equals(groupId)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE, "offline.group-removed");
        }
    }

    @Override
    public void onGroupAdded(@Nullable HueBridge bridge, FullGroup group) {
        if (group.getId().equals(groupId)) {
            onGroupStateChanged(bridge, group);
        }
    }

    /**
     * Schedules restoration of the alert item state to {@link LightStateConverter#ALERT_MODE_NONE} after a given time.
     * <br>
     * Based on the initial command:
     * <ul>
     * <li>For {@link LightStateConverter#ALERT_MODE_SELECT} restoration will be triggered after <strong>2
     * seconds</strong>.
     * <li>For {@link LightStateConverter#ALERT_MODE_LONG_SELECT} restoration will be triggered after <strong>15
     * seconds</strong>.
     * </ul>
     * This method also cancels any previously scheduled restoration.
     *
     * @param command The {@link Command} sent to the item
     */
    private void scheduleAlertStateRestore(Command command) {
        cancelScheduledFuture();
        int delay = getAlertDuration(command);

        if (delay > 0) {
            scheduledFuture = scheduler.schedule(() -> {
                updateState(CHANNEL_ALERT, new StringType(LightStateConverter.ALERT_MODE_NONE));
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * This method will cancel previously scheduled alert item state
     * restoration.
     */
    private void cancelScheduledFuture() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
    }

    /**
     * This method returns the time in <strong>milliseconds</strong> after
     * which, the state of the alert item has to be restored to {@link LightStateConverter#ALERT_MODE_NONE}.
     *
     * @param command The initial command sent to the alert item.
     * @return Based on the initial command will return:
     *         <ul>
     *         <li><strong>2000</strong> for {@link LightStateConverter#ALERT_MODE_SELECT}.
     *         <li><strong>15000</strong> for {@link LightStateConverter#ALERT_MODE_LONG_SELECT}.
     *         <li><strong>-1</strong> for any command different from the previous two.
     *         </ul>
     */
    private int getAlertDuration(Command command) {
        int delay;
        switch (command.toString()) {
            case LightStateConverter.ALERT_MODE_LONG_SELECT:
                delay = 15000;
                break;
            case LightStateConverter.ALERT_MODE_SELECT:
                delay = 2000;
                break;
            default:
                delay = -1;
                break;
        }

        return delay;
    }
}
