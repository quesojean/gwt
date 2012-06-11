/*
 * Copyright 2012 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package elemental.html;

import elemental.events.*;
import elemental.util.*;
import elemental.dom.*;
import elemental.html.*;
import elemental.css.*;
import elemental.stylesheets.*;

import java.util.Date;

/**
  * 
  */
public interface AudioParam {

  float getDefaultValue();

  float getMaxValue();

  float getMinValue();

  String getName();

  int getUnits();

  float getValue();

  void setValue(float arg);

  void cancelScheduledValues(float startTime);

  void exponentialRampToValueAtTime(float value, float time);

  void linearRampToValueAtTime(float value, float time);

  void setTargetValueAtTime(float targetValue, float time, float timeConstant);

  void setValueAtTime(float value, float time);

  void setValueCurveAtTime(Float32Array values, float time, float duration);
}