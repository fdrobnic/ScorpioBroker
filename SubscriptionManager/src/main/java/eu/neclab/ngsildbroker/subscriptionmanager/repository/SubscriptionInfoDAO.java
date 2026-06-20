package eu.neclab.ngsildbroker.subscriptionmanager.repository;

import com.github.jsonldjava.core.JsonLDService;
import com.google.common.collect.Lists;
import com.google.common.collect.Table;

import eu.neclab.ngsildbroker.commons.constants.AppConstants;
import eu.neclab.ngsildbroker.commons.constants.NGSIConstants;
import eu.neclab.ngsildbroker.commons.datatypes.EntityInfo;
import eu.neclab.ngsildbroker.commons.datatypes.RegistrationEntry;
import eu.neclab.ngsildbroker.commons.datatypes.Subscription;
import eu.neclab.ngsildbroker.commons.datatypes.requests.subscription.DeleteSubscriptionRequest;
import eu.neclab.ngsildbroker.commons.datatypes.requests.subscription.SubscriptionRequest;
import eu.neclab.ngsildbroker.commons.datatypes.requests.subscription.UpdateSubscriptionRequest;
import eu.neclab.ngsildbroker.commons.datatypes.terms.ScopeQueryTerm;
import eu.neclab.ngsildbroker.commons.enums.ErrorType;
import eu.neclab.ngsildbroker.commons.exceptions.ResponseException;
import eu.neclab.ngsildbroker.commons.storage.ConnectionManager;
import eu.neclab.ngsildbroker.commons.tools.DBUtil;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import io.smallrye.mutiny.tuples.Tuple3;
import io.smallrye.mutiny.tuples.Tuple4;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
@Startup
public class SubscriptionInfoDAO {

	Logger logger = LoggerFactory.getLogger(SubscriptionInfoDAO.class);

	@Inject
	ConnectionManager connectionManager;

	@Inject
	JsonLDService ldService;

	public Uni<Void> createSubscription(SubscriptionRequest request, String contextId) {

		return connectionManager
				.executeQuery(request.getTenant(),
						"INSERT INTO subscriptions(subscription_id, subscription, context) VALUES ($1, $2, $3)",
						Tuple.of(request.getId(), new JsonObject(request.getPayload()), contextId), true)
				.onItem()
				.transformToUni(rows -> Uni.createFrom().voidItem());

	}

	public Uni<RowSet<Row>> getInitialNotificationData(SubscriptionRequest subscriptionRequest) {
		Tuple tuple = Tuple.tuple();
		StringBuilder sql = new StringBuilder("with a as (select cs_id from csourceinformation WHERE ");
		boolean sqlAdded = false;
		int dollar = 1;
		Subscription subscription = subscriptionRequest.getSubscription();
		Iterator<EntityInfo> it = subscription.getEntities().iterator();
		while (it.hasNext()) {
			EntityInfo entityInformation = it.next();
			sql.append("(");
			if (entityInformation.getId() != null) {
				sql.append("((e_id is null or e_id  = $" + dollar + ") and (e_id_p is null or e_id_p ~ $" + dollar
						+ "))");
				dollar++;

				tuple.addString(entityInformation.getId().toString());
				if (entityInformation.getTypeTerm() != null) {
					sql.append(" and ");
				}
			} else if (entityInformation.getIdPattern() != null) {
				sql.append("((e_id is null or $" + dollar + " ~ e_id) and (e_id_p is null or e_id_p = $" + dollar
						+ "))");
				dollar++;
				tuple.addString(entityInformation.getIdPattern());
				if (entityInformation.getTypeTerm() != null) {
					sql.append(" and ");
				}
			}
			if (entityInformation.getTypeTerm() != null) {
				// dollar = entityInformation.getTypeTerm().toSql(sql, tuple, dollar);
				Set<String> types = entityInformation.getTypeTerm().getAllTypes();
				sql.append("e_type IN (");
				for (String type : types) {
					sql.append('$');
					sql.append(dollar);
					sql.append(',');
					dollar++;
					tuple.addString(type);
				}
				sql.setCharAt(sql.length() - 1, ')');

			}
			sql.append(")");
			if (it.hasNext()) {
				sql.append(" and ");
			}
			sqlAdded = true;
		}

		if (subscription.getAttributeNames() != null) {
			if (sqlAdded) {
				sql.append(" and ");
			}
			sql.append("(e_prop is null or e_prop = any($" + dollar + ")) and (e_rel is null or e_rel = any($"
					+ dollar + "))");
			tuple.addArrayOfString(subscription.getAttributeNames().toArray(new String[0]));
			dollar++;
			sqlAdded = true;
		}

		if (subscription.getLdGeoQuery() != null) {
			if (sqlAdded) {
				sql.append(" and ");
			}
			try {
				Tuple2<StringBuilder, Integer> tmp = subscription.getLdGeoQuery().getGeoSQLQuery(tuple, dollar,
						"i_location");
				sql.append(tmp.getItem1().toString());
				dollar = tmp.getItem2();
				sqlAdded = true;
			} catch (ResponseException e) {
				return Uni.createFrom().failure(e);
			}
		}

		if (subscription.getScopeQuery() != null) {
			if (sqlAdded) {
				sql.append(" and ");
			}
			sql.append("(scopes IS NULL OR ");
			ScopeQueryTerm current = subscription.getScopeQuery();
			while (current != null) {
				sql.append(" matchscope(scopes, " + current.getSQLScopeQuery() + ")");

				if (current.hasNext()) {
					if (current.isNextAnd()) {
						sql.append(" and ");
					} else {
						sql.append(" or ");
					}
				}
				current = current.getNext();
			}
			sql.append(")");

		}

		sql.append(") select csource.reg from a left join csource on a.cs_id = csource.id");
		if (subscription.getCsf() != null) {
			// if (sqlAdded) {
			// sql += " and ";
			// }
			// dollar++;
		}

		// logger.debug("SQL I noti: " + sql);
		// logger.debug("Tuple I noti: " + tuple.deepToString());
		return connectionManager.executeQuery(subscriptionRequest.getTenant(), sql.toString(), tuple, false);
	}

	public Uni<Tuple2<Map<String, Object>, Object>> updateSubscription(UpdateSubscriptionRequest request,
			String contextId) {
		String sql = "UPDATE subscriptions SET subscription=subscription || $2, context=$3 WHERE subscription_id=$1 RETURNING subscriptions.subscription";
		Tuple tuple = Tuple.of(request.getId(), new JsonObject(request.getPayload()), contextId);
		return connectionManager.executeQuery(request.getTenant(), sql, tuple, false).onItem()
				.transformToUni(rows -> {
					if (rows.size() == 0) {
						return Uni.createFrom()
								.failure(new ResponseException(ErrorType.NotFound, request.getId() + " not found"));
					} else {
						return Uni.createFrom()
								.item(Tuple2.of(rows.iterator().next().getJsonObject("subscription").getMap(),
										request.getContext().serialize().get("@context")));
					}
				});
	}

	public Uni<RowSet<Row>> deleteSubscription(DeleteSubscriptionRequest request) {
		String sql = "DELETE FROM subscriptions WHERE subscription_id=$1";
		Tuple tuple = Tuple.of(request.getId());
		return connectionManager.executeQuery(request.getTenant(), sql, tuple, false).onItem().transformToUni(rows -> {
			if (rows.rowCount() == 0) {
				return Uni.createFrom().failure(new ResponseException(ErrorType.NotFound));
			}
			return Uni.createFrom().item(rows);
		});
	}

	public Uni<RowSet<Row>> getAllSubscriptions(String tenant, int limit, int offset) {
		String sql = "SELECT subscription, count(*) over() FROM subscriptions LIMIT $1 OFFSET $2";
		Tuple tuple = Tuple.of(limit, offset);
		return connectionManager.executeQuery(tenant, sql, tuple, false);
	}

	public Uni<RowSet<Row>> getSubscription(String tenant, String subscriptionId) {
		String sql = "SELECT subscription FROM subscriptions WHERE subscription_id=$1";
		Tuple tuple = Tuple.of(subscriptionId);
		return connectionManager.executeQuery(tenant, sql, tuple, false);
	}

	public Uni<Void> updateNotificationSuccess(String tenant, String id, String date) {

		String sql = "UPDATE subscriptions SET subscription = jsonb_set(jsonb_set(jsonb_set(subscription, '{"
				+ NGSIConstants.NGSI_LD_TIMES_SENT + "}', jsonb_build_array(jsonb_build_object('"
				+ NGSIConstants.JSON_LD_VALUE + "', (subscription #>> '{" + NGSIConstants.NGSI_LD_TIMES_SENT + ",0,"
				+ NGSIConstants.JSON_LD_VALUE + "}')::integer + 1)), true), '{" + NGSIConstants.NGSI_LD_LAST_SUCCESS
				+ "}', jsonb_build_array(jsonb_build_object('" + NGSIConstants.JSON_LD_TYPE + "', '"
				+ NGSIConstants.NGSI_LD_DATE_TIME + "', '" + NGSIConstants.JSON_LD_VALUE + "', $1::text)), true),'{"
				+ NGSIConstants.NGSI_LD_LAST_NOTIFICATION + "}', jsonb_build_array(jsonb_build_object('"
				+ NGSIConstants.JSON_LD_TYPE + "', '" + NGSIConstants.NGSI_LD_DATE_TIME + "', '"
				+ NGSIConstants.JSON_LD_VALUE + "', $1::text)), true) WHERE subscription_id=$2";
		Tuple tuple = Tuple.of(date, id);

		return connectionManager.executeQuery(tenant, sql, tuple, false).onFailure().retry().atMost(3).onItem()
				.transformToUni(t -> Uni.createFrom().voidItem());
	}

	public Uni<Void> updateNotificationFailure(String tenant, String id, String date) {
		String sql = "UPDATE subscriptions SET subscription = jsonb_set(jsonb_set(jsonb_set(subscription, '{"
				+ NGSIConstants.NGSI_LD_TIMES_FAILED + "}', jsonb_build_array(jsonb_build_object('"
				+ NGSIConstants.JSON_LD_VALUE + "', (subscription #>> '{" + NGSIConstants.NGSI_LD_TIMES_FAILED + ",0,"
				+ NGSIConstants.JSON_LD_VALUE + "}')::integer + 1)), true), '{" + NGSIConstants.NGSI_LD_LAST_FAILURE
				+ "}', jsonb_build_array(jsonb_build_object('" + NGSIConstants.JSON_LD_TYPE + "', '"
				+ NGSIConstants.NGSI_LD_DATE_TIME + "', '" + NGSIConstants.JSON_LD_VALUE + "', $1::text)), true),'{"
				+ NGSIConstants.NGSI_LD_LAST_NOTIFICATION + "}', jsonb_build_array(jsonb_build_object('"
				+ NGSIConstants.JSON_LD_TYPE + "', '" + NGSIConstants.NGSI_LD_DATE_TIME + "', '"
				+ NGSIConstants.JSON_LD_VALUE + "', $1::text)), true) WHERE subscription_id=$2";
		Tuple tuple = Tuple.of(date, id);
		return connectionManager.executeQuery(tenant, sql, tuple, false).onItem()
				.transformToUni(t -> Uni.createFrom().voidItem());
	}

	@SuppressWarnings("unchecked")
	public Uni<List<Tuple4<String, Map<String, Object>, String, Map<String, Object>>>> loadSubscriptions() {

		return connectionManager.executeQuery(null, "select tenant_id from tenant", null, false).onItem()
				.transformToUni(rows -> {
					List<Uni<RowSet<Row>>> unis = Lists.newArrayList();
					rows.forEach(row -> {

						unis.add(connectionManager.executeQuery(row.getString(0), "SELECT '" + row.getString(0)
								+ "', subscription, context FROM subscriptions", null, false));
					});
					unis.add(connectionManager.executeQuery(null, "SELECT '" + AppConstants.INTERNAL_NULL_KEY
							+ "', subscription, context FROM subscriptions", null, false));
					return Uni.combine().all().unis(unis).with(list -> {
						List<Tuple4<String, Map<String, Object>, String, Map<String, Object>>> result = new ArrayList<>();
						return connectionManager.executeQuery(null,
								"select jsonb_object_agg(id,body) as col from public.contexts", null, false).onItem()
								.transform(rows1 -> {
									JsonObject jsonContexts = null;
									if (rows1.size() > 0) {
										jsonContexts = rows1.iterator().next().getJsonObject(0);
									}
									Map<String, Object> mapContexts = jsonContexts == null ? null : jsonContexts.getMap();
									for (Object obj : list) {
										RowSet<Row> rowset = (RowSet<Row>) obj;
										rowset.forEach(row -> {
											String tenant = row.getString(0);
											Map<String, Object> sub = row.getJsonObject(1).getMap();
											String ctxId = row.getString(2);
											result.add(Tuple4.of(tenant, sub, ctxId,
													getContextFromDefaultContexts(mapContexts, ctxId, sub, tenant)));
										});
									}
									return result;
								});
					}).onItem().transformToUni(x -> x);
				});

	}

	public Uni<Tuple3<Map<String, Object>, String, Map<String, Object>>> loadSubscription(String tenant, String id) {
		return connectionManager.executeQuery(tenant,
				"SELECT subscription, context FROM subscriptions WHERE subscription_id=$1", Tuple.of(id), false)
				.onItem().transformToUni(rows -> {
					if (rows.size() == 0) {
						Tuple3<Map<String, Object>, String, Map<String, Object>> r = Tuple3.of(null, null, null);
						return Uni.createFrom().item(r);
					}
					Row first = rows.iterator().next();
					Map<String, Object> subscription = first.getJsonObject(0).getMap();
					String contextId = first.getString(1);
					return loadContextFromDefaultTenant(contextId, subscription, tenant).onItem()
							.transform(ctxMap -> Tuple3.of(subscription, contextId, ctxMap));

				});
	}

	private Uni<Map<String, Object>> loadContextFromDefaultTenant(String contextId, Map<String, Object> subscription,
			String tenant) {
		String lookupId = contextId == null ? AppConstants.INTERNAL_NULL_KEY : contextId;
		return connectionManager.executeQuery(null, "SELECT body FROM contexts WHERE id=$1", Tuple.of(lookupId), false)
				.onItem().transformToUni(rows -> {
					if (rows.size() > 0) {
						return Uni.createFrom().item(rows.iterator().next().getJsonObject(0).getMap());
					}
					if (!AppConstants.INTERNAL_NULL_KEY.equals(lookupId)) {
						logger.warn("Failed to read context " + lookupId + " for subscription "
								+ getSubscriptionId(subscription) + " on tenant " + tenant
								+ ". Falling back to default context.");
						return connectionManager
								.executeQuery(null, "SELECT body FROM contexts WHERE id=$1",
										Tuple.of(AppConstants.INTERNAL_NULL_KEY), false)
								.onItem().transform(defaultRows -> {
									if (defaultRows.size() == 0) {
										logger.error("Failed to read default context for subscription "
												+ getSubscriptionId(subscription) + " on tenant " + tenant);
										return null;
									}
									return defaultRows.iterator().next().getJsonObject(0).getMap();
								});
					}
					logger.error("Failed to read default context for subscription " + getSubscriptionId(subscription)
							+ " on tenant " + tenant);
					return Uni.createFrom().item((Map<String, Object>) null);
				});
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getContextFromDefaultContexts(Map<String, Object> contexts, String contextId,
			Map<String, Object> subscription, String tenant) {
		if (contexts == null) {
			logger.error("Failed to read context for subscription " + getSubscriptionId(subscription) + " on tenant "
					+ tenant);
			return null;
		}
		Map<String, Object> ctxMap = (Map<String, Object>) contexts.get(contextId);
		if (ctxMap == null) {
			ctxMap = (Map<String, Object>) contexts.get(AppConstants.INTERNAL_NULL_KEY);
			if (ctxMap == null) {
				logger.error("Failed to read default context for subscription " + getSubscriptionId(subscription)
						+ " on tenant " + tenant);
			} else if (contextId != null) {
				logger.warn("Failed to read context " + contextId + " for subscription "
						+ getSubscriptionId(subscription) + " on tenant " + tenant
						+ ". Falling back to default context.");
			}
		}
		return ctxMap;
	}

	private Object getSubscriptionId(Map<String, Object> subscription) {
		return subscription == null ? null : subscription.get(NGSIConstants.JSON_LD_ID);
	}

	public Uni<RowSet<Row>> getRegById(String tenant, String id) {
		return connectionManager.executeQuery(tenant, "SELECT reg FROM csource WHERE id = $1", Tuple.of(id), false);
	}

	public Uni<Table<String, String, List<RegistrationEntry>>> getAllRegistries() {
		return DBUtil.getAllRegistries(connectionManager, ldService,
				"SELECT cs_id, c_id, e_id, e_id_p, e_type, e_prop, e_rel, ST_AsGeoJSON(i_location), scopes, EXTRACT(MILLISECONDS FROM expires), endpoint, tenant_id, headers, reg_mode, createEntity, updateEntity, appendAttrs, updateAttrs, deleteAttrs, deleteEntity, createBatch, upsertBatch, updateBatch, deleteBatch, upsertTemporal, appendAttrsTemporal, deleteAttrsTemporal, updateAttrsTemporal, deleteAttrInstanceTemporal, deleteTemporal, mergeEntity, replaceEntity, replaceAttrs, mergeBatch, retrieveEntity, queryEntity, queryBatch, retrieveTemporal, queryTemporal, retrieveEntityTypes, retrieveEntityTypeDetails, retrieveEntityTypeInfo, retrieveAttrTypes, retrieveAttrTypeDetails, retrieveAttrTypeInfo, createSubscription, updateSubscription, retrieveSubscription, querySubscription, deleteSubscription, queryEntityMap, createEntityMap, updateEntityMap, deleteEntityMap, retrieveEntityMap, csource_Alias FROM csourceinformation WHERE queryentity OR querybatch OR retrieveentity OR createSubscription",
				logger);

	}

}
