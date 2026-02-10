////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Tomasz Rup
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License
//
// Author: Tomasz Rup
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package com.tomaszrup.groovyls;

/**
 * Parameters for the {@code groovy/statusUpdate} custom notification.
 *
 * <p>Sent from the server to the client to drive status bar transitions
 * without relying on fragile log-message string matching.</p>
 */
public class StatusUpdateParams {

    /** Server state: {@code "importing"}, {@code "ready"}, or {@code "error"}. */
    private String state;

    /** Optional human-readable detail message for the current state. */
    private String message;

    public StatusUpdateParams() {
    }

    public StatusUpdateParams(String state, String message) {
        this.state = state;
        this.message = message;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
