/**
 * NDKarte - Native Android Navigation Application
 *
 * Main entry point for NativeActivity-based application.
 * Target: Android 14+ (API 34), 1920x1200 landscape
 */

#include <android/log.h>
#include <android_native_app_glue.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "NDKarte"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * Application state structure
 */
typedef struct {
    struct android_app* app;

    // EGL state
    EGLDisplay display;
    EGLSurface surface;
    EGLContext context;

    // Window dimensions
    int32_t width;
    int32_t height;

    // App state flags
    bool initialized;
    bool has_focus;
} AppState;

/**
 * Initialize EGL context and surface
 */
static bool init_display(AppState* state) {
    // Get default display
    state->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (state->display == EGL_NO_DISPLAY) {
        LOGE("Failed to get EGL display");
        return false;
    }

    // Initialize EGL
    if (!eglInitialize(state->display, NULL, NULL)) {
        LOGE("Failed to initialize EGL");
        return false;
    }

    // Choose EGL config
    const EGLint config_attribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_NONE
    };

    EGLConfig config;
    EGLint num_configs;
    if (!eglChooseConfig(state->display, config_attribs, &config, 1, &num_configs) || num_configs == 0) {
        LOGE("Failed to choose EGL config");
        return false;
    }

    // Create window surface
    state->surface = eglCreateWindowSurface(state->display, config, state->app->window, NULL);
    if (state->surface == EGL_NO_SURFACE) {
        LOGE("Failed to create EGL surface");
        return false;
    }

    // Create OpenGL ES 3.0 context
    const EGLint context_attribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };

    state->context = eglCreateContext(state->display, config, EGL_NO_CONTEXT, context_attribs);
    if (state->context == EGL_NO_CONTEXT) {
        LOGE("Failed to create EGL context");
        return false;
    }

    // Make context current
    if (!eglMakeCurrent(state->display, state->surface, state->surface, state->context)) {
        LOGE("Failed to make EGL context current");
        return false;
    }

    // Get surface dimensions
    eglQuerySurface(state->display, state->surface, EGL_WIDTH, &state->width);
    eglQuerySurface(state->display, state->surface, EGL_HEIGHT, &state->height);

    LOGI("Display initialized: %dx%d", state->width, state->height);
    LOGI("GL_VENDOR: %s", glGetString(GL_VENDOR));
    LOGI("GL_RENDERER: %s", glGetString(GL_RENDERER));
    LOGI("GL_VERSION: %s", glGetString(GL_VERSION));

    // Set viewport
    glViewport(0, 0, state->width, state->height);

    state->initialized = true;
    return true;
}

/**
 * Cleanup EGL resources
 */
static void terminate_display(AppState* state) {
    if (state->display != EGL_NO_DISPLAY) {
        eglMakeCurrent(state->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

        if (state->context != EGL_NO_CONTEXT) {
            eglDestroyContext(state->display, state->context);
        }

        if (state->surface != EGL_NO_SURFACE) {
            eglDestroySurface(state->display, state->surface);
        }

        eglTerminate(state->display);
    }

    state->display = EGL_NO_DISPLAY;
    state->surface = EGL_NO_SURFACE;
    state->context = EGL_NO_CONTEXT;
    state->initialized = false;

    LOGI("Display terminated");
}

/**
 * Render a single frame
 */
static void render_frame(AppState* state) {
    if (!state->initialized) {
        return;
    }

    // Clear to dark blue (navigation app style)
    glClearColor(0.1f, 0.15f, 0.2f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    // TODO: Render map and UI elements here

    // Swap buffers
    eglSwapBuffers(state->display, state->surface);
}

/**
 * Handle application commands (lifecycle events)
 */
static void handle_cmd(struct android_app* app, int32_t cmd) {
    AppState* state = (AppState*)app->userData;

    switch (cmd) {
        case APP_CMD_INIT_WINDOW:
            LOGI("APP_CMD_INIT_WINDOW");
            if (app->window != NULL) {
                init_display(state);
            }
            break;

        case APP_CMD_TERM_WINDOW:
            LOGI("APP_CMD_TERM_WINDOW");
            terminate_display(state);
            break;

        case APP_CMD_GAINED_FOCUS:
            LOGI("APP_CMD_GAINED_FOCUS");
            state->has_focus = true;
            break;

        case APP_CMD_LOST_FOCUS:
            LOGI("APP_CMD_LOST_FOCUS");
            state->has_focus = false;
            // Still render one frame when losing focus
            render_frame(state);
            break;

        case APP_CMD_PAUSE:
            LOGI("APP_CMD_PAUSE");
            break;

        case APP_CMD_RESUME:
            LOGI("APP_CMD_RESUME");
            break;

        case APP_CMD_DESTROY:
            LOGI("APP_CMD_DESTROY");
            break;

        default:
            break;
    }
}

/**
 * Handle input events
 */
static int32_t handle_input(struct android_app* app, AInputEvent* event) {
    AppState* state = (AppState*)app->userData;
    (void)state;  // Will be used for touch handling later

    int32_t event_type = AInputEvent_getType(event);

    if (event_type == AINPUT_EVENT_TYPE_MOTION) {
        // Touch event
        int32_t action = AMotionEvent_getAction(event);
        float x = AMotionEvent_getX(event, 0);
        float y = AMotionEvent_getY(event, 0);

        switch (action & AMOTION_EVENT_ACTION_MASK) {
            case AMOTION_EVENT_ACTION_DOWN:
                LOGI("Touch DOWN at (%.1f, %.1f)", x, y);
                break;
            case AMOTION_EVENT_ACTION_UP:
                LOGI("Touch UP at (%.1f, %.1f)", x, y);
                break;
            case AMOTION_EVENT_ACTION_MOVE:
                // Log move events sparingly to avoid spam
                break;
        }
        return 1;  // Event handled
    }

    return 0;  // Event not handled
}

/**
 * Main entry point - called by android_native_app_glue
 */
void android_main(struct android_app* app) {
    LOGI("NDKarte starting...");

    // Initialize application state
    AppState state = {0};
    state.app = app;
    state.display = EGL_NO_DISPLAY;
    state.surface = EGL_NO_SURFACE;
    state.context = EGL_NO_CONTEXT;

    // Set up callbacks
    app->userData = &state;
    app->onAppCmd = handle_cmd;
    app->onInputEvent = handle_input;

    // Main loop
    while (true) {
        int events;
        struct android_poll_source* source;

        // Process all pending events
        while (ALooper_pollAll(state.has_focus ? 0 : -1, NULL, &events, (void**)&source) >= 0) {
            if (source != NULL) {
                source->process(app, source);
            }

            // Check if app should exit
            if (app->destroyRequested) {
                LOGI("Destroy requested, exiting...");
                terminate_display(&state);
                return;
            }
        }

        // Render frame if we have focus
        if (state.has_focus) {
            render_frame(&state);
        }
    }
}
