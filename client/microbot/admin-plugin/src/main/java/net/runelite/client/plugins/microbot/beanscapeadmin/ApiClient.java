package net.runelite.client.plugins.microbot.beanscapeadmin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/** Thin blocking client for the server's /v1 admin API. Always call from a worker thread. */
final class ApiClient
{
	/** A failed call, with a message fit to show the admin. */
	static final class ApiException extends Exception
	{
		final int status;

		ApiException(String message, int status)
		{
			super(message);
			this.status = status;
		}
	}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
	private final Gson gson = new Gson();
	private final Supplier<AdminSettings> settings;

	ApiClient(Supplier<AdminSettings> settings)
	{
		this.settings = settings;
	}

	static String enc(String value)
	{
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	JsonObject get(String path) throws ApiException
	{
		return send(path, null, false);
	}

	JsonObject post(String path, JsonObject body) throws ApiException
	{
		return send(path, body == null ? new JsonObject() : body, false);
	}

	JsonObject delete(String path) throws ApiException
	{
		return send(path, null, true);
	}

	private JsonObject send(String path, JsonObject body, boolean delete) throws ApiException
	{
		AdminSettings cfg = settings.get();
		if (!cfg.configured())
		{
			throw new ApiException("Not set up. The launcher writes beanscape-admin.json for owners.", 0);
		}
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(cfg.apiUrl + path))
			.timeout(Duration.ofSeconds(8))
			.header("Authorization", "Bearer " + cfg.token)
			.header("Accept", "application/json");
		if (delete)
		{
			request.DELETE();
		}
		else if (body != null)
		{
			request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)));
		}
		else
		{
			request.GET();
		}
		try
		{
			HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			JsonObject json = parse(response.body());
			if (response.statusCode() / 100 != 2)
			{
				String message = json.has("error") ? json.get("error").getAsString() : "Server answered " + response.statusCode();
				throw new ApiException(response.statusCode() == 401 ? "Token rejected. Ask an owner for a new one." : message, response.statusCode());
			}
			return json;
		}
		catch (InterruptedException ex)
		{
			Thread.currentThread().interrupt();
			throw new ApiException("Cancelled", 0);
		}
		catch (IOException | IllegalArgumentException ex)
		{
			throw new ApiException("Can't reach the server. Is Tailscale connected?", 0);
		}
	}

	private JsonObject parse(String raw)
	{
		try
		{
			JsonObject json = gson.fromJson(raw, JsonObject.class);
			return json == null ? new JsonObject() : json;
		}
		catch (RuntimeException ex)
		{
			return new JsonObject();
		}
	}
}
